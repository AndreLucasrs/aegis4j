package dev.aegis4j.guardrails.builtin;

import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One-level text de-obfuscation used by {@link EncodedInjectionGuard}: given a
 * piece of text, produces every plausible "decoded view" of it (Base64, hex,
 * binary, decimal char codes, Morse, percent/unicode/HTML escapes, invisible
 * Unicode tag characters, ROT13, reversed text, homoglyph/leetspeak folding...).
 * The guard applies this repeatedly to peel off layered encodings.
 *
 * <p>Every regex here is deliberately linear-time (no nested quantifiers over
 * overlapping classes), because the input is attacker-controlled.
 */
final class PayloadDecoder {

    /** A derived text and whether it came from decoding an embedded payload (vs. a mere re-spelling of the same text). */
    record Derived(String text, boolean decodedPayload) {
    }

    private static final Pattern BASE64 = Pattern.compile("[A-Za-z0-9+/_-]{16,}={0,2}");
    // Character-class repetition only: group repetition like (?:..){n,} recurses per iteration and overflows the stack on big inputs.
    private static final Pattern MORSE_RUN = Pattern.compile("[.\\-/| \\t]{9,}");
    private static final Pattern TOKEN_SEPARATORS = Pattern.compile("[\\s:,\\-]+");
    private static final Pattern PERCENT = Pattern.compile("%[0-9a-fA-F]{2}");
    private static final Pattern UNICODE_ESCAPE = Pattern.compile("\\\\u([0-9a-fA-F]{4})|&#[xX]([0-9a-fA-F]{1,6});|&#(\\d{1,7});");
    private static final Pattern HEX_PREFIX = Pattern.compile("(?i)\\\\x|0x");
    private static final Pattern ACCENTS = Pattern.compile("\\p{M}+");

    private static final Map<String, Character> MORSE = morseAlphabet();
    private static final Map<Character, Character> CONFUSABLES = confusables();

    private PayloadDecoder() {
    }

    /**
     * @param respell whether to also produce re-spellings (reverse, ROT13, ...). The guard passes {@code false}
     *                for texts that are themselves re-spellings, so views don't multiply combinatorially.
     */
    static List<Derived> derive(String text, boolean respell) {
        List<Derived> out = new ArrayList<>();

        if (respell) {
            addRespellings(out, text);
        }
        addPayloads(out, text);
        return out;
    }

    private static void addRespellings(List<Derived> out, String text) {
        add(out, text, normalize(text), false);
        add(out, text, ACCENTS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll(""), false);
        add(out, text, new StringBuilder(text).reverse().toString(), false);
        add(out, text, rot13(text), false);
        add(out, text, leet(text), false);
        add(out, text, joinSpacedLetters(text), false);
        add(out, text, unescape(text), false);
    }

    private static void addPayloads(List<Derived> out, String text) {
        add(out, text, decodeTagCharacters(text), true);
        for (String s : decodeBase64(text)) {
            add(out, text, s, true);
        }
        for (String s : decodeHex(text)) {
            add(out, text, s, true);
        }
        for (String s : decodeBinary(text)) {
            add(out, text, s, true);
        }
        for (String s : decodeDecimal(text)) {
            add(out, text, s, true);
        }
        for (String s : decodeMorse(text)) {
            add(out, text, s, true);
        }
    }

    private static void add(List<Derived> out, String original, String candidate, boolean payload) {
        if (candidate != null && !candidate.isBlank() && !candidate.equals(original)) {
            out.add(new Derived(candidate, payload));
        }
    }

    // ---------------------------------------------------------------- views

    /** NFKC (folds full-width/compat forms), drops invisible/format characters, folds common Latin lookalikes. */
    static String normalize(String text) {
        String nfkc = Normalizer.normalize(text, Normalizer.Form.NFKC);
        StringBuilder sb = new StringBuilder(nfkc.length());
        nfkc.codePoints().forEach(cp -> {
            int type = Character.getType(cp);
            if (type == Character.FORMAT || isTagCharacter(cp) || cp == 0x00AD) {
                return;
            }
            if (cp < 0x10000) {
                Character folded = CONFUSABLES.get((char) cp);
                if (folded != null) {
                    sb.append(folded.charValue());
                    return;
                }
            }
            sb.appendCodePoint(cp);
        });
        return sb.toString();
    }

    private static String rot13(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 'a' && c <= 'z') {
                sb.append((char) ('a' + (c - 'a' + 13) % 26));
            } else if (c >= 'A' && c <= 'Z') {
                sb.append((char) ('A' + (c - 'A' + 13) % 26));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String leet(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            sb.append(switch (c) {
                case '0' -> 'o';
                case '1', '!' -> 'i';
                case '3' -> 'e';
                case '4', '@' -> 'a';
                case '5', '$' -> 's';
                case '7' -> 't';
                default -> c;
            });
        }
        return sb.toString();
    }

    /** "i g n o r e" / "i.g.n.o.r.e" / "i-g-n-o-r-e" -> "ignore". Runs of 4+ single letters only. */
    private static String joinSpacedLetters(String text) {
        int n = text.length();
        StringBuilder sb = new StringBuilder(n);
        int i = 0;
        while (i < n) {
            if (Character.isLetter(text.charAt(i)) && (i == 0 || !Character.isLetterOrDigit(text.charAt(i - 1)))) {
                int j = i;
                int letters = 1;
                while (j + 2 < n && isLetterSeparator(text.charAt(j + 1)) && Character.isLetter(text.charAt(j + 2))) {
                    j += 2;
                    letters++;
                }
                boolean bounded = j + 1 >= n || !Character.isLetterOrDigit(text.charAt(j + 1));
                if (letters >= 4 && bounded) {
                    for (int k = i; k <= j; k += 2) {
                        sb.append(text.charAt(k));
                    }
                } else {
                    sb.append(text, i, j + 1);
                }
                i = j + 1;
            } else {
                sb.append(text.charAt(i++));
            }
        }
        return sb.toString();
    }

    private static boolean isLetterSeparator(char c) {
        return c == ' ' || c == '.' || c == '-' || c == '_' || c == '*';
    }

    /** Percent-encoding, {@code \\uXXXX}, and numeric HTML entities. */
    private static String unescape(String text) {
        String result = text;
        if (PERCENT.matcher(result).find()) {
            try {
                result = URLDecoder.decode(result, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ignored) {
                // malformed escape sequence: leave the text as-is
            }
        }
        Matcher m = UNICODE_ESCAPE.matcher(result);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int cp;
            try {
                if (m.group(1) != null) {
                    cp = Integer.parseInt(m.group(1), 16);
                } else if (m.group(2) != null) {
                    cp = Integer.parseInt(m.group(2), 16);
                } else {
                    cp = Integer.parseInt(m.group(3));
                }
            } catch (NumberFormatException e) {
                continue;
            }
            m.appendReplacement(sb, Character.isValidCodePoint(cp)
                    ? Matcher.quoteReplacement(new String(Character.toChars(cp)))
                    : Matcher.quoteReplacement(m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ------------------------------------------------------------ payloads

    private static boolean isTagCharacter(int cp) {
        return cp >= 0xE0000 && cp <= 0xE007F;
    }

    /** "ASCII smuggling": invisible Unicode tag characters (U+E00xx) that mirror ASCII. */
    private static String decodeTagCharacters(String text) {
        StringBuilder sb = new StringBuilder();
        text.codePoints().forEach(cp -> {
            if (isTagCharacter(cp)) {
                int ascii = cp - 0xE0000;
                if (ascii >= 0x20 && ascii < 0x7F) {
                    sb.append((char) ascii);
                }
            }
        });
        return sb.toString();
    }

    private static List<String> decodeBase64(String text) {
        List<String> out = new ArrayList<>();
        // Also try with whitespace removed, so "aWdub3Jl\nIHByZXZpb3Vz..." (MIME-style wrapping) is seen as one blob.
        for (String source : new String[] {text, text.replaceAll("\\s+", "")}) {
            Matcher m = BASE64.matcher(source);
            while (m.find()) {
                String token = m.group().replace('-', '+').replace('_', '/');
                int pad = token.indexOf('=');
                String body = pad >= 0 ? token.substring(0, pad) : token;
                if (body.length() % 4 == 1) {
                    continue;
                }
                String padded = body + "=".repeat((4 - body.length() % 4) % 4);
                try {
                    addIfReadable(out, Base64.getDecoder().decode(padded));
                } catch (IllegalArgumentException ignored) {
                    // not valid Base64
                }
            }
        }
        return out;
    }

    private static List<String> decodeHex(String text) {
        List<String> out = new ArrayList<>();
        String[] tokens = TOKEN_SEPARATORS.split(HEX_PREFIX.matcher(text).replaceAll(" "));
        // (a) consecutive hex-only tokens ("6967 6e6f" or one long blob); (b) just the runs of 2-digit tokens ("69 67 6e"),
        // which survives a stray hex-looking word ("bad", "face") sitting next to the payload.
        for (boolean pairsOnly : new boolean[] {false, true}) {
            StringBuilder run = new StringBuilder();
            for (int i = 0; i <= tokens.length; i++) {
                String t = i < tokens.length ? tokens[i] : "";
                if (!t.isEmpty() && isAll(t, "0123456789abcdefABCDEF") && (!pairsOnly || t.length() == 2)) {
                    run.append(t);
                } else {
                    decodeHexDigits(run, out);
                    run.setLength(0);
                }
            }
        }
        return out;
    }

    private static void decodeHexDigits(CharSequence digits, List<String> out) {
        int usable = digits.length() - digits.length() % 2;
        if (usable < 16) {
            return;
        }
        byte[] bytes = new byte[usable / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) ((Character.digit(digits.charAt(2 * i), 16) << 4) | Character.digit(digits.charAt(2 * i + 1), 16));
        }
        addIfReadable(out, bytes);
    }

    private static List<String> decodeBinary(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder run = new StringBuilder();
        String[] tokens = TOKEN_SEPARATORS.split(text);
        for (int i = 0; i <= tokens.length; i++) {
            String t = i < tokens.length ? tokens[i] : "";
            if (!t.isEmpty() && t.length() % 8 == 0 && isAll(t, "01")) {
                run.append(t);
            } else {
                if (run.length() >= 32) {
                    byte[] bytes = new byte[run.length() / 8];
                    for (int b = 0; b < bytes.length; b++) {
                        bytes[b] = (byte) Integer.parseInt(run.substring(8 * b, 8 * b + 8), 2);
                    }
                    addIfReadable(out, bytes);
                }
                run.setLength(0);
            }
        }
        return out;
    }

    private static List<String> decodeDecimal(String text) {
        List<String> out = new ArrayList<>();
        List<Byte> run = new ArrayList<>();
        String[] tokens = TOKEN_SEPARATORS.split(text.replace("-", " "));
        for (int i = 0; i <= tokens.length; i++) {
            String t = i < tokens.length ? tokens[i] : "";
            if (t.length() >= 2 && t.length() <= 3 && isAll(t, "0123456789") && Integer.parseInt(t) >= 9 && Integer.parseInt(t) < 127) {
                run.add((byte) Integer.parseInt(t));
            } else {
                if (run.size() >= 8) {
                    byte[] bytes = new byte[run.size()];
                    for (int b = 0; b < bytes.length; b++) {
                        bytes[b] = run.get(b);
                    }
                    addIfReadable(out, bytes);
                }
                run.clear();
            }
        }
        return out;
    }

    private static boolean isAll(String s, String alphabet) {
        for (int i = 0; i < s.length(); i++) {
            if (alphabet.indexOf(s.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }

    private static List<String> decodeMorse(String text) {
        List<String> out = new ArrayList<>();
        String folded = text
                .replace('•', '.').replace('·', '.').replace('∙', '.').replace('●', '.')
                .replace('–', '-').replace('—', '-').replace('−', '-').replace('―', '-').replace('_', '-');
        Matcher m = MORSE_RUN.matcher(folded);
        while (m.find()) {
            String run = m.group().trim();
            if (run.split("\\s+").length < 5) {
                continue;
            }
            StringBuilder decoded = new StringBuilder();
            boolean ok = true;
            // Words are separated by "/", "|" or 2+ spaces; letters by a single space.
            for (String word : run.split("\\s*[/|]\\s*|\\s{2,}")) {
                if (word.isBlank()) {
                    continue;
                }
                for (String letter : word.trim().split("\\s+")) {
                    Character c = MORSE.get(letter);
                    if (c == null) {
                        ok = false;
                        break;
                    }
                    decoded.append(c.charValue());
                }
                if (!ok) {
                    break;
                }
                decoded.append(' ');
            }
            // "- - - - - -" (a rule line) decodes to "TTTTTT"; require some variety before calling it text.
            if (ok && decoded.chars().filter(c -> c != ' ').distinct().count() >= 3) {
                out.add(decoded.toString().trim());
            }
        }
        return out;
    }

    // -------------------------------------------------------------- helpers

    /** Adds the bytes as text only if they are valid UTF-8 and look like prose, not binary noise. */
    private static void addIfReadable(List<String> out, byte[] bytes) {
        if (bytes.length < 6) {
            return;
        }
        String decoded;
        try {
            decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return;
        }
        long printable = decoded.chars().filter(c -> !Character.isISOControl(c) || c == '\n' || c == '\t' || c == '\r').count();
        long texty = decoded.chars().filter(c -> Character.isLetter(c) || Character.isWhitespace(c)).count();
        long morseLike = decoded.chars().filter(c -> c == '.' || c == '-' || c == '/' || c == '|' || c == ' ').count();
        if (printable >= decoded.length() * 0.9 && (texty >= decoded.length() * 0.6 || morseLike >= decoded.length() * 0.9)) {
            out.add(decoded);
        }
    }

    private static Map<String, Character> morseAlphabet() {
        String[][] table = {
                {"A", ".-"}, {"B", "-..."}, {"C", "-.-."}, {"D", "-.."}, {"E", "."}, {"F", "..-."},
                {"G", "--."}, {"H", "...."}, {"I", ".."}, {"J", ".---"}, {"K", "-.-"}, {"L", ".-.."},
                {"M", "--"}, {"N", "-."}, {"O", "---"}, {"P", ".--."}, {"Q", "--.-"}, {"R", ".-."},
                {"S", "..."}, {"T", "-"}, {"U", "..-"}, {"V", "...-"}, {"W", ".--"}, {"X", "-..-"},
                {"Y", "-.--"}, {"Z", "--.."},
                {"0", "-----"}, {"1", ".----"}, {"2", "..---"}, {"3", "...--"}, {"4", "....-"},
                {"5", "....."}, {"6", "-...."}, {"7", "--..."}, {"8", "---.."}, {"9", "----."},
        };
        Map<String, Character> map = new java.util.HashMap<>();
        for (String[] row : table) {
            map.put(row[1], row[0].charAt(0));
        }
        return Map.copyOf(map);
    }

    private static Map<Character, Character> confusables() {
        // Cyrillic / Greek letters visually identical to Latin ones.
        String[] pairs = {
                "аa", "еe", "оo", "рp", "сc", "хx", "уy", "іi", "јj", "ѕs", "ԁd", "ԍg", "һh", "ӏl",
                "οo", "αa", "εe", "νv", "κk",
        };
        Map<Character, Character> map = new java.util.HashMap<>();
        for (String pair : pairs) {
            map.put(pair.charAt(0), pair.charAt(1));
        }
        return Map.copyOf(map);
    }
}
