package org.purehvm.ops

import groovy.transform.Canonical

/** Runs a bash script as root on one HVM host. The Morpheus implementation goes through the agent. */
interface HostRunner {
    Result run(String host, String script)

    /** Same, with positional arguments ($1, $2, ...) for the script. */
    default Result run(String host, String script, List<String> args) {
        run(host, ScriptWrapper.withArgs(script, args))
    }

    @Canonical
    static class Result {
        int exitCode
        String output

        boolean isOk() { exitCode == 0 }

        /** KEY=value lines; repeated keys (FILE=) keep every value under kvAll. */
        Map<String, String> getKv() {
            (output ?: '').readLines().findAll { it ==~ /[A-Z_]+=.*/ }.collectEntries { String l ->
                int i = l.indexOf('=')
                [(l.substring(0, i)): l.substring(i + 1)]
            }
        }

        List<String> all(String key) {
            (output ?: '').readLines().findAll { it.startsWith(key + '=') }*.substring(key.length() + 1)
        }

        /** KEY=<a>|<b> lines as [a, b] (b may itself contain '|'). */
        List<List<String>> pairs(String key) {
            all(key).collect { it.split(/\|/, 2) as List<String> }
        }

        String getError() {
            kv.ERROR ?: (ok ? null : "exit ${exitCode}")
        }
    }
}

/** Wraps a script so it runs under bash with sudo regardless of the agent's login shell. */
class ScriptWrapper {
    /** {@code script} preceded by "set -- args"; args are restricted to IP-address/word characters so they need no quoting. */
    static String withArgs(String script, List<String> args) {
        args.each {
            if (!(it ==~ /[A-Za-z0-9.:_-]+/)) {
                throw new IllegalArgumentException("unsafe script argument ${it}")
            }
        }
        args ? "set -- ${args.join(' ')}\n${script}".toString() : script
    }

    static String wrap(String script) {
        "echo '${script.getBytes('UTF-8').encodeBase64()}' | base64 -d | sudo bash -s"
    }
}
