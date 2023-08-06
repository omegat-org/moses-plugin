# OmegaT Moses MT connector plugin

This is spin-out Moses MT connector for OmegaT 5.8.0 or later.
Moses MT connector used Apache XML-RPC client library that is known to have CRITICAL vulnerability(CVE-2019-17570).
It is why Moses MT connector is removed from OmegaT main distribution.

The plugin here uses the forked and patched version of xml-rpc client, that uses the patch
from [Fedra Linux Bugzilla #1775193](https://bugzilla.redhat.com/show_bug.cgi?id=1775193)

The plugin resolves the vulnerability issue and published as an experimental release.

## Version and development status

The Current version is 1.0.0.

## Installation

You can get a plugin jar file from zip distribution file.
OmegaT plugin should be placed in `$HOME/.omegat/plugin` or `C:\Program Files\OmegaT\plugin`
depending on your operating system.

## License

This project is distributed under the GNU general public license version 3 or later.

## CAUTION

Moses MT connector in OmegaT 5.7.1 and before has VULNERABILITY ranked as CRITICAL.

### Deserialization of Untrusted Data (CVE-2019-17570)

org.apache.xmlrpc:xmlrpc-client is a Java implementation of XML-RPC, a popular protocol that uses XML over HTTP
to implement remote procedure calls.
Affected versions of this package are vulnerable to Deserialization of Untrusted Data.
A flaw was discovered where the XMLRPC client implementation performed deserialization of
the server-side exception serialized in the faultCause attribute of XMLRPC error response messages.
A malicious or compromised XMLRPC server could possibly use this flaw to execute arbitrary code with the privileges
of an application using the Apache XMLRPC client library.
