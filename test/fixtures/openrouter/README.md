# Captured response fixtures

`response-39.json` and `response-41.json` contain only the decoded response bodies
from the preserved authored-tree experiment receipts with the same numbers.
They have no HTTP request headers or credentials. Receipt 39 includes readable
reasoning plus summary/encrypted detail records; receipt 41 demonstrates opaque
reasoning without exposed text. These are replay fixtures, not new paid calls.

Source pin for the original adapter:
`0570e9459b8751d4a0c3dc1cd48595804426a84d`.
The test intercepts only final HTTP transport; public completion validation,
request serialization and response normalization are real.
