import com.acme.gateway.RouteRules

// The CPS side of the library: everything that touches Jenkins steps. The rules
// themselves live in com.acme.gateway.RouteRules and stay pure.
//
// Requires the "Pipeline Utility Steps" plugin (readYaml, readJSON, findFiles).

/** Reads every route file under routes/, at any depth, in a stable order. */
List load(String dir = 'routes') {
    def paths = []
    for (String glob : ["${dir}/**/*.yaml", "${dir}/**/*.yml", "${dir}/**/*.json"]) {
        for (def f : findFiles(glob: glob)) { paths.add(f.path) }
    }
    def entries = []
    for (String path : RouteRules.sorted(paths)) {
        def doc = path.endsWith('.json') ? readJSON(file: path) : readYaml(file: path)
        def routes = (doc instanceof List) ? doc : (doc?.routes ?: [])
        if (!routes) {
            echo "WARNING: ${path} declares no route"
            continue
        }
        for (def r : routes) { entries.add([file: path, route: r]) }
    }
    return entries
}

/**
 * Validates the routes of this repository against its own policy.yaml.
 * Fails listing every error at once — one round trip per PR, not one per rule.
 */
List validate() {
    def policy = readYaml(file: 'policy.yaml')
    def entries = load()
    echo "${entries.size()} route(s) under routes/"

    def errs = RouteRules.all(entries, policy, policy.metadata?.approvedPublic ?: [])
    if (errs) {
        echo "\n=== ${errs.size()} error(s) ==="
        for (String e : errs) { echo "  - ${e}" }
        error('Route validation failed.')
    }
    echo 'Route validation passed.'
    return entries
}

/**
 * Starts a throwaway gateway on the route files of the workspace and asserts it comes
 * up with all of them. The Config Server source and the file source share the very same
 * RouteDefinitionFileParser, so loading the directory here exercises the real parser.
 *
 * This is the only check that proves the files start; the static rules cannot.
 */
void smoke(Map cfg) {
    withEnv(["SMOKE_IMAGE=${cfg.image}", "SMOKE_EXPECTED=${cfg.expected}"]) {
        sh '''
            set -eu
            cid=$(docker run -d --rm -P \
                -v "$PWD/routes:/routes:ro" \
                "$SMOKE_IMAGE" \
                --server.port=8080 \
                --spring.cloud.gateway.server.webflux.routes-configserver.enabled=false \
                --spring.cloud.gateway.server.webflux.routes-files.enabled=true \
                --spring.cloud.gateway.server.webflux.routes-files.locations='file:/routes/**/*.yaml' \
                --management.endpoints.web.exposure.include=health,gateway)
            trap 'docker rm -f "$cid" >/dev/null 2>&1 || true' EXIT

            addr=$(docker port "$cid" 8080/tcp | head -1)
            up=0
            i=0
            while [ "$i" -lt 40 ]; do
                if curl -fsS "http://$addr/actuator/health" >/dev/null 2>&1; then up=1; break; fi
                i=$((i + 1)); sleep 3
            done
            if [ "$up" -ne 1 ]; then
                echo "The gateway did not start on these route files:"
                docker logs "$cid"
                exit 1
            fi

            live=$(curl -fsS "http://$addr/actuator/gateway/routes" | grep -c '"route_id"' || true)
            echo "gateway loaded $live route(s), the repository declares $SMOKE_EXPECTED"
            if [ "$live" -lt "$SMOKE_EXPECTED" ]; then
                echo "Routes were silently dropped by the parser:"
                docker logs "$cid"
                exit 1
            fi
        '''
    }
}

/**
 * Writes the aggregate the gateway fetches as its single route resource. The name is
 * the same in every gateway, so nothing downstream has to know which one this is.
 *
 * A full regeneration, never a merge: a route file deleted upstream must disappear
 * from the aggregate.
 */
String aggregate(List entries, String outDir = 'dist') {
    sh "rm -rf ${outDir} && mkdir -p ${outDir}"
    writeYaml(file: "${outDir}/routes.yaml", data: [routes: entries.collect { it.route }], overwrite: true)
    echo "aggregated ${entries.size()} route(s) into ${outDir}/routes.yaml"
    return "${outDir}/routes.yaml"
}

/**
 * Commits the aggregate onto the environment branch of this gateway's own Config Server
 * repository, which no developer sees and no human commits to.
 *
 * Guards the blast radius first: an aggregate that suddenly lost most of its routes is
 * a mistake upstream, not a deployment.
 */
void publish(Map cfg) {
    int now = cfg.expected as int
    double floor = (cfg.minRetainedRatio ?: 0.5) as double

    withEnv(["CFG_REPO=${cfg.repo}", "CFG_BRANCH=${cfg.branch}"]) {
        sshagent([cfg.credentialsId]) {
            sh 'rm -rf .config-repo && git clone --depth 1 --branch "$CFG_BRANCH" "$CFG_REPO" .config-repo'

            int previous = fileExists('.config-repo/dist/routes.yaml')
                ? ((readYaml(file: '.config-repo/dist/routes.yaml').routes ?: []).size()) : 0
            echo "${cfg.branch}: ${previous} route(s) published, ${now} in this build"

            if (now == 0) {
                error("${cfg.branch}: refusing to publish an empty aggregate.")
            }
            if (previous > 0 && now < previous * floor && !cfg.allowShrink) {
                error("${cfg.branch}: the aggregate drops from ${previous} to ${now} route(s). " +
                      'Re-run with ALLOW_SHRINK if the removal is intended.')
            }

            // One config repository per gateway and one branch per environment, so this
            // push races with nothing: no rebase loop to write.
            sh '''
                set -eu
                mkdir -p .config-repo/dist
                cp dist/routes.yaml .config-repo/dist/routes.yaml
                cd .config-repo
                git add dist
                if git diff --cached --quiet; then echo "No route change to publish."; exit 0; fi
                git -c user.name='jenkins' -c user.email='jenkins@acme.corp' \
                    commit -m "routes: $CFG_BRANCH from ${GIT_COMMIT} (build ${BUILD_NUMBER})"
                git push origin "$CFG_BRANCH"
            '''
        }
    }
}
