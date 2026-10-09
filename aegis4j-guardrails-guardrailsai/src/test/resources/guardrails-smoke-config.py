# Minimal Guardrails Server config used by GuardrailsAiServerIntegrationTest.
#
#   pip install guardrails-ai guardrails-api
#   guardrails start --config guardrails-smoke-config.py --port 8000
#   GUARDRAILS_SERVER_URL=http://localhost:8000 ./gradlew :aegis4j-guardrails-guardrailsai:test
#
# Uses a custom validator (no Guardrails Hub token needed) under three failure actions.
from guardrails import Guard
from guardrails.validators import (
    FailResult,
    PassResult,
    Validator,
    register_validator,
)


@register_validator(name="smoke/no-badword", data_type="string")
class NoBadword(Validator):
    def _validate(self, value, metadata):
        if "badword" in value.lower():
            return FailResult(
                error_message="contains badword",
                fix_value=value.lower().replace("badword", "<redacted>"),
            )
        return PassResult()


guard_fix = Guard(name="smoke-fix").use(NoBadword, on_fail="fix")
guard_exception = Guard(name="smoke-exception").use(NoBadword, on_fail="exception")
guard_noop = Guard(name="smoke-noop").use(NoBadword, on_fail="noop")
