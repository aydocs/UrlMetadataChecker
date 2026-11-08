# Security Policy

## Reporting a vulnerability

Please do not open a public issue for a security vulnerability. Contact the
repository maintainer privately with a description, reproduction steps, and
the affected version. Do not include real credentials or personal data.

## Scope

UrlChecker is a local command-line tool and makes outbound requests to URLs
provided by the operator. It is not intended to run as an unauthenticated
public HTTP service. Keep response-size and timeout limits enabled when using
it in automation.
