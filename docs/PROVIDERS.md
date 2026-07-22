# Cloud providers

| Stable ID | UI name | Wire format | Default endpoint | Key |
| --- | --- | --- | --- | --- |
| `groq` | Groq | OpenAI-compatible plus guarded Qwen options | `https://api.groq.com/openai/v1` | required |
| `openai` | OpenAI | OpenAI-compatible | `https://api.openai.com/v1` | required |
| `anthropic` | Anthropic | native Messages API | `https://api.anthropic.com/v1` | required |
| `ollama` | Ollama (local) | OpenAI-compatible | `http://localhost:11434/v1` | optional |
| `custom` | Custom | OpenAI-compatible | user supplied | optional |

The selected model, optional override URL, and temperature are persisted per
provider. API keys are separately encrypted per stable ID. A saved session also
records the provider/model actually used.

## Endpoint validation

Endpoints must be absolute `http` or `https` URLs with a host and without embedded
credentials, query parameters, or fragments. HTTP is accepted only for exact
device loopback hosts. All remote hosts require HTTPS. Focal intentionally does
not add certificate pinning.

## Requests and errors

OpenAI-compatible requests contain system/user messages, model, temperature,
maximum output tokens, and streaming mode. Only the verified Groq/Qwen policy
adds `reasoning_effort`, `reasoning_format`, `top_p`, and usage-stream fields.
Anthropic requests use a top-level system prompt, user message array,
`anthropic-version`, and `x-api-key`.

401/403 responses become credential/access errors. 429 responses honor bounded
server delay hints and the Groq limiter can reconcile verified quota headers.
Transient 5xx and network failures use bounded retries; cancellation cancels the
active call. Provider body messages are bounded and credential-redacted before
they reach UI or session state.

Focal never changes provider automatically after an error. Costs, model access,
quotas, data retention, and account policy are controlled by the selected provider
and must be reviewed by the user.

## Validation status

Payload construction, capability isolation, error mapping, redaction, queueing,
and migration behavior have JVM tests. Live authenticated calls are not performed
by repository tests and remain an owner/device release gate.
