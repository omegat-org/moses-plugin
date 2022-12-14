# OmegaT Moses MT connector plugin

This is spin-out Moses MT connector for OmegaT.
Moses MT connector uses Apache XML-RPC client library that is known to have CRITICAL vulnerability and
it is not fixed.

It is why Moses MT connector is removed from OmegaT main distribution.

The plugin here is a spin-out project that is as same as the connector in OmegaT 5.7.1.

## CAUTION

OmegaT Moses MT connector has VULNERABILITY ranked as CRITICAL.

### What you should aware of?

If moses MT service is compromised, OmegaT connector might execute arbitrary code from attacker on your PC.

### Deserialization of Untrusted Data

org.apache.xmlrpc:xmlrpc-client is a Java implementation of XML-RPC, a popular protocol that uses XML over HTTP
to implement remote procedure calls.
Affected versions of this package are vulnerable to Deserialization of Untrusted Data.
A flaw was discovered where the XMLRPC client implementation performed deserialization of
the server-side exception serialized in the faultCause attribute of XMLRPC error response messages.
A malicious or compromised XMLRPC server could possibly use this flaw to execute arbitrary code with the privileges
of an application using the Apache XMLRPC client library.

## Installation

You can get a plugin jar file from zip distribution file.
OmegaT plugin should be placed in `$HOME/.omegat/plugin` or `C:\Program Files\OmegaT\plugin`
depending on your operating system.

## License

This project is distributed under the GNU general public license version 3 or later.
