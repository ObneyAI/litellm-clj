# OpenRouter structured contracts and observations

## Request fidelity

OpenRouter requests preserve explicit function `:strict` flags and normalized
`:response-format` controls. Both `:json-schema` and wire `:json_schema` envelopes
are accepted; literal property names inside the schema are not renamed.
An `:extra-body` override that conflicts with normalized response format or
reasoning controls fails before HTTP dispatch.

## Returned evidence

Blocking completion messages retain refusal presence, provider reasoning text
and opaque typed reasoning details. Usage retains returned token details, cost,
cost details and BYOK status. Missing evidence stays absent; explicit null stays
present. These are provider observations, not generated application outputs.
Opaque reasoning blocks are preserved without interpreting or decoding them.
Streaming reasoning delta accumulation is not provided by this change.

## Verification and compatibility

Public completion tests capture serialized HTTP requests and replay captured
response bodies through the real adapter. The complete non-live unit gate passed
283 tests and 1,009 assertions across 19 namespaces. Credential-gated live cases
were disabled in that gate; real captured ORC compatibility execution is separate.

The branch includes the existing enhancements history (model overrides,
multimodal/tool support, streaming usage, async response waits and reasoning
controls), followed by these structured-contract repairs. The focused Allium
contract is `specs/openrouter-contract.allium`: check/analyse report one external
caller source-hint warning, no errors or analysis findings. The caller belongs
to consuming applications and has no governing specification in this library.
