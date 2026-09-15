package com.acme.gateway

import com.cloudbees.groovy.cps.NonCPS

/**
 * The validation rules over gateway route definitions.
 *
 * Every method here is @NonCPS and pure: it takes parsed data and returns a list of
 * error strings. No Jenkins step, no I/O — so the rules can be unit-tested outside
 * Jenkins, and CPS never has to serialise their closures.
 */
class RouteRules implements Serializable {

    // ---------------------------------------------------------------- helpers

    /** Sorting a list inside a CPS method is a known trap; do it here instead. */
    @NonCPS
    static List sorted(List values) {
        return values.sort(false)
    }

    /** Values of a named predicate, shorthand and object form alike. */
    @NonCPS
    static List predicateValues(Map route, String name) {
        def out = []
        for (def p : (route.predicates ?: [])) {
            if (p instanceof CharSequence) {
                String s = p.toString()
                if (s.startsWith(name + '=')) {
                    out.addAll(s.substring(name.length() + 1).split(',').collect { it.trim() })
                }
            }
            else if (p instanceof Map && p.name == name) {
                def args = (p.args ?: [:])
                // The object form names its argument (pattern, patterns, ...) but Spring
                // also accepts positional keys (_genkey_0), hence the values() fallback.
                def raw = args.pattern ?: args.patterns ?: args.values().join(',')
                out.addAll(raw.toString().split(',').collect { it.trim() })
            }
        }
        return out
    }

    /** Names of the filters a route declares, shorthand and object form alike. */
    @NonCPS
    static List filterNames(Map route) {
        def out = []
        for (def f : (route.filters ?: [])) {
            if (f instanceof CharSequence) {
                String s = f.toString()
                out.add(s.contains('=') ? s.substring(0, s.indexOf('=')) : s)
            }
            else if (f instanceof Map && f.name) {
                out.add(f.name.toString())
            }
        }
        return out
    }

    /** The part of a path pattern before the first wildcard, cut at a segment boundary. */
    @NonCPS
    static String literalPrefix(String pattern) {
        int cut = -1
        for (String marker : ['*', '{', '?']) {
            int i = pattern.indexOf(marker)
            if (i >= 0 && (cut < 0 || i < cut)) { cut = i }
        }
        if (cut < 0) { return pattern }
        String head = pattern.substring(0, cut)
        int slash = head.lastIndexOf('/')
        return (slash > 0) ? head.substring(0, slash) : '/'
    }

    /** True when `a` covers `b`: same pattern, or `a` an ancestor segment of `b`. */
    @NonCPS
    static boolean covers(String a, String b) {
        if (a == b) { return true }
        return b.startsWith(a) && (a.endsWith('/') || b.charAt(a.length()) == ('/' as char))
    }

    // ------------------------------------------------------------- the rules

    /** Every route carries an id, a uri and a predicate; ids are lowercase slugs. */
    @NonCPS
    static List schema(List entries) {
        def errs = []
        def slug = ~/^[a-z0-9]([a-z0-9_-]*[a-z0-9])?$/
        for (def e : entries) {
            def r = e.route
            if (!r.id) { errs.add("${e.file}: a route has no 'id'"); continue }
            String where = "${e.file} [${r.id}]"
            if (!slug.matcher(r.id.toString()).matches()) {
                errs.add("${where}: 'id' must be a lowercase slug ([a-z0-9-_])")
            }
            if (!r.uri) { errs.add("${where}: no 'uri'") }
            if (!r.predicates) { errs.add("${where}: no 'predicates' — the route would match everything") }
            if (r.order != null && !(r.order instanceof Number)) { errs.add("${where}: 'order' is not a number") }
        }
        return errs
    }

    /** No two routes of the gateway share an id, whatever the file or the team. */
    @NonCPS
    static List uniqueIds(List entries) {
        def errs = []
        def seen = [:]
        for (def e : entries) {
            String id = e.route.id?.toString()
            if (!id) { continue }
            if (seen.containsKey(id)) { errs.add("duplicate route id '${id}': ${seen[id]} and ${e.file}") }
            else { seen[id] = e.file }
        }
        return errs
    }

    /**
     * Path patterns of two different files that overlap on the same Host. Catches prefix
     * shadowing (/api/** against /api/orders/**), not full pattern equivalence. Overlap
     * inside a single file is left alone: there the author controls the order.
     */
    @NonCPS
    static List pathCollisions(List entries) {
        def errs = []
        def flat = []
        for (def e : entries) {
            def hosts = predicateValues(e.route, 'Host')
            for (String p : predicateValues(e.route, 'Path')) {
                flat.add([file: e.file, id: e.route.id, prefix: literalPrefix(p), pattern: p, hosts: hosts])
            }
        }
        for (int i = 0; i < flat.size(); i++) {
            for (int j = i + 1; j < flat.size(); j++) {
                def a = flat[i]
                def b = flat[j]
                if (a.file == b.file) { continue }
                boolean sameHost = a.hosts.isEmpty() || b.hosts.isEmpty() || !a.hosts.intersect(b.hosts).isEmpty()
                if (!sameHost) { continue }
                if (covers(a.prefix, b.prefix) || covers(b.prefix, a.prefix)) {
                    errs.add("path overlap: '${a.pattern}' (${a.id}, ${a.file}) and '${b.pattern}' (${b.id}, ${b.file})")
                }
            }
        }
        return errs
    }

    /** The target uri matches the gateway allowlist, and is TLS when the policy demands it. */
    @NonCPS
    static List uriAllowlist(List entries, Map policy) {
        def errs = []
        def allow = (policy.uri?.allow ?: []).collect { java.util.regex.Pattern.compile(it.toString()) }
        boolean requireTls = (policy.uri?.requireTls == true)
        for (def e : entries) {
            String uri = e.route.uri?.toString()
            if (!uri) { continue }
            String where = "${e.file} [${e.route.id}]"
            if (requireTls && uri.startsWith('http://')) {
                errs.add("${where}: plain http target '${uri}' is refused on this gateway")
            }
            if (allow && !allow.any { it.matcher(uri).matches() }) {
                errs.add("${where}: target '${uri}' matches no entry of the allowlist")
            }
        }
        return errs
    }

    /** Filters the gateway imposes, and filters it forbids. */
    @NonCPS
    static List filters(List entries, Map policy) {
        def errs = []
        def required = (policy.filters?.required ?: []).collect { it.toString() }
        def forbidden = (policy.filters?.forbidden ?: []).collect { it.toString() }
        for (def e : entries) {
            def names = filterNames(e.route)
            String where = "${e.file} [${e.route.id}]"
            for (String f : required) {
                if (!names.contains(f)) { errs.add("${where}: filter '${f}' is required on this gateway") }
            }
            for (String f : forbidden) {
                if (names.contains(f)) { errs.add("${where}: filter '${f}' is forbidden on this gateway") }
            }
        }
        return errs
    }

    /**
     * metadata.public exempts the route from Spring Security (routes-security), so it is
     * only accepted for an id the security team listed in the approval file.
     */
    @NonCPS
    static List publicMetadata(List entries, List approved) {
        def errs = []
        for (def e : entries) {
            if (e.route.metadata?.public == true && !approved.contains(e.route.id?.toString())) {
                errs.add("${e.file} [${e.route.id}]: 'metadata.public: true' is not in the approved list")
            }
        }
        return errs
    }

    /** Every route names an owning team, so it still has one in six months. */
    @NonCPS
    static List owner(List entries) {
        def errs = []
        for (def e : entries) {
            if (!e.route.metadata?.owner) { errs.add("${e.file} [${e.route.id}]: no 'metadata.owner'") }
        }
        return errs
    }

    /** Nothing that looks like a credential is written into a route file. */
    @NonCPS
    static List noInlineSecrets(List entries) {
        def errs = []
        def suspects = [
            (~/(?i)basic\s+[a-z0-9+\/=]{16,}/)                             : 'inline Basic credentials',
            (~/(?i)bearer\s+ey[a-z0-9._-]{20,}/)                           : 'inline Bearer token',
            (~/(?i)(client[-_]?secret|password|api[-_]?key)\s*[:=]\s*\S+/) : 'inline secret',
            (~/\/\/[^\/:@\s]+:[^\/@\s]+@/)                                 : 'credentials in the uri',
        ]
        for (def e : entries) {
            String body = e.route.toString()
            for (def s : suspects) {
                if (s.key.matcher(body).find()) {
                    errs.add("${e.file} [${e.route.id}]: ${s.value} — use a secret reference resolved at runtime")
                }
            }
        }
        return errs
    }

    /** Runs every rule and returns the errors, empty when the routes are clean. */
    @NonCPS
    static List all(List entries, Map policy, List approvedPublic) {
        def errs = []
        errs.addAll(schema(entries))
        errs.addAll(uniqueIds(entries))
        errs.addAll(pathCollisions(entries))
        errs.addAll(uriAllowlist(entries, policy))
        errs.addAll(filters(entries, policy))
        errs.addAll(publicMetadata(entries, approvedPublic))
        errs.addAll(owner(entries))
        errs.addAll(noInlineSecrets(entries))
        return errs
    }
}
