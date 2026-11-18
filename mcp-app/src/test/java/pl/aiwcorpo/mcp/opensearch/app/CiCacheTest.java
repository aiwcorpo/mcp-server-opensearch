package pl.aiwcorpo.mcp.opensearch.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * The GitLab pipeline: a merge request runs code nobody has reviewed, so its job may read a Maven cache but never write
 * one that the default-branch job restores.
 */
class CiCacheTest {

    private static final Path PIPELINE = Path.of("..", ".gitlab-ci.yml");
    private static final Set<String> NOT_JOBS = Set.of("default", "variables", "stages", "include", "workflow");

    @Test
    void aMergeRequestJobOnlyReadsACacheOfItsOwn() throws IOException {
        Map<String, Object> pipeline;
        try (Reader reader = Files.newBufferedReader(PIPELINE)) {
            pipeline = new Yaml().load(reader);
        }
        Map<String, Object> defaults = map(pipeline.get("default"));

        Set<String> mainKeys = jobs(pipeline).entrySet().stream()
                .filter(job -> runsOn(job.getValue(), "$CI_DEFAULT_BRANCH"))
                .map(job -> String.valueOf(cache(job.getValue(), defaults).get("key")))
                .collect(Collectors.toSet());
        Map<String, Map<String, Object>> mergeRequestJobs = jobs(pipeline).entrySet().stream()
                .filter(job -> runsOn(job.getValue(), "merge_request_event"))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        assertThat(mainKeys).isNotEmpty();
        assertThat(mergeRequestJobs).isNotEmpty();
        mergeRequestJobs.forEach((name, job) -> {
            Map<String, Object> cache = cache(job, defaults);
            assertThat(cache.get("policy")).as("cache policy of %s", name).isEqualTo("pull");
            assertThat(mainKeys).as("cache key of %s", name).doesNotContain(String.valueOf(cache.get("key")));
        });
    }

    private static Map<String, Map<String, Object>> jobs(Map<String, Object> pipeline) {
        return pipeline.entrySet().stream()
                .filter(entry -> !entry.getKey().startsWith(".") && !NOT_JOBS.contains(entry.getKey())
                        && entry.getValue() instanceof Map<?, ?>)
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> map(entry.getValue())));
    }

    private static boolean runsOn(Map<String, Object> job, String condition) {
        Object rules = job.get("rules");
        return rules instanceof List<?> list && list.stream()
                .map(rule -> rule instanceof Map<?, ?> m ? String.valueOf(m.get("if")) : "")
                .anyMatch(rule -> rule.contains(condition));
    }

    private static Map<String, Object> cache(Map<String, Object> job, Map<String, Object> defaults) {
        return map(Objects.requireNonNullElse(job.get("cache"), defaults.get("cache")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value == null ? Map.of() : (Map<String, Object>) value;
    }
}
