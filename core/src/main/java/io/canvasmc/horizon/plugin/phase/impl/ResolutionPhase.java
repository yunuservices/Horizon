package io.canvasmc.horizon.plugin.phase.impl;

import io.canvasmc.horizon.HorizonLoader;
import io.canvasmc.horizon.logger.Logger;
import io.canvasmc.horizon.plugin.LoadContext;
import io.canvasmc.horizon.plugin.data.HorizonPluginMetadata;
import io.canvasmc.horizon.plugin.phase.Phase;
import io.canvasmc.horizon.plugin.phase.PhaseException;
import io.canvasmc.horizon.transformer.MixinTransformationImpl;
import io.canvasmc.horizon.util.FileJar;
import io.canvasmc.horizon.util.MinecraftVersion;
import io.canvasmc.horizon.util.Pair;
import io.canvasmc.horizon.util.tree.ObjectTree;
import org.jspecify.annotations.NonNull;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ResolutionPhase implements Phase<Set<Pair<FileJar, HorizonPluginMetadata>>, Set<Pair<FileJar, HorizonPluginMetadata>>> {

    private static final Pattern COMPARATOR_PATTERN =
        Pattern.compile("^(>=|<=|>|<|=)?\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern VERSION_TOKEN_PATTERN = Pattern.compile("\\d+|[a-zA-Z]+");
    private static final Logger LOGGER = Logger.fork(HorizonLoader.LOGGER, "plugin_resolution");
    private static final Set<String> SYSTEM_DEPENDENCY_KEYS = Set.of("minecraft", "java", "asm", "horizon", "server", "bootstrap");

    private static boolean matchesGenericInteger(@NonNull String constraint, int current) {
        Matcher matcher = COMPARATOR_PATTERN.matcher(constraint.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid integer version constraint: " + constraint);
        }

        String operator = matcher.group(1);
        int target = Integer.parseInt(matcher.group(2));

        if (operator == null || operator.equals("=")) {
            return current == target;
        }

        return switch (operator) {
            case ">" -> current > target;
            case ">=" -> current >= target;
            case "<" -> current < target;
            case "<=" -> current <= target;
            default -> throw new IllegalStateException("Unhandled operator: " + operator);
        };
    }

    private static boolean matches(
        @NonNull String constraint,
        @NonNull MinecraftVersion currentVersion
    ) {
        Predicate<MinecraftVersion> predicate = parseMinecraftConstraint(constraint);
        return predicate.test(currentVersion);
    }

    private static @NonNull Predicate<MinecraftVersion> parseMinecraftConstraint(@NonNull String raw) {
        String input = raw.trim().toLowerCase(Locale.ROOT);

        Matcher matcher = COMPARATOR_PATTERN.matcher(input);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid version constraint: " + raw);
        }

        String operator = matcher.group(1);
        String versionPart = matcher.group(2).trim();

        if (versionPart.endsWith("*")) {
            String prefix = versionPart.substring(0, versionPart.length() - 1);
            return v -> v.getId().toLowerCase(Locale.ROOT).startsWith(prefix);
        }

        MinecraftVersion target = MinecraftVersion.fromStringId(versionPart);

        if (operator == null || operator.equals("=")) {
            return v -> v == target;
        }

        return switch (operator) {
            case ">" -> v -> v.isNewerThan(target);
            case ">=" -> v -> v.isNewerThanOrEqualTo(target);
            case "<" -> v -> v.isOlderThan(target);
            case "<=" -> v -> v.isOlderThanOrEqualTo(target);
            default -> throw new IllegalStateException("Unhandled operator: " + operator);
        };
    }

    private static boolean matchesLooseVersion(@NonNull String constraint, @NonNull String currentVersion) {
        Matcher matcher = COMPARATOR_PATTERN.matcher(constraint.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid version constraint: " + constraint);
        }

        String operator = matcher.group(1);
        String target = matcher.group(2).trim();

        if (target.endsWith("*")) {
            String prefix = target.substring(0, target.length() - 1).toLowerCase(Locale.ROOT);
            String current = currentVersion.toLowerCase(Locale.ROOT);
            return current.startsWith(prefix);
        }

        int compare = compareLooseVersions(currentVersion, target);
        if (operator == null || operator.equals("=")) {
            return compare == 0;
        }

        return switch (operator) {
            case ">" -> compare > 0;
            case ">=" -> compare >= 0;
            case "<" -> compare < 0;
            case "<=" -> compare <= 0;
            default -> throw new IllegalStateException("Unhandled operator: " + operator);
        };
    }

    private static int compareLooseVersions(@NonNull String currentVersion, @NonNull String targetVersion) {
        List<String> currentTokens = tokenizeVersion(currentVersion);
        List<String> targetTokens = tokenizeVersion(targetVersion);
        int max = Math.max(currentTokens.size(), targetTokens.size());
        for (int i = 0; i < max; i++) {
            String left = i < currentTokens.size() ? currentTokens.get(i) : "0";
            String right = i < targetTokens.size() ? targetTokens.get(i) : "0";
            int compare = compareVersionToken(left, right);
            if (compare != 0) {
                return compare;
            }
        }
        return 0;
    }

    private static @NonNull List<String> tokenizeVersion(@NonNull String version) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = VERSION_TOKEN_PATTERN.matcher(version.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        if (tokens.isEmpty()) {
            return List.of("0");
        }
        return tokens;
    }

    private static int compareVersionToken(@NonNull String left, @NonNull String right) {
        boolean leftNumeric = left.chars().allMatch(Character::isDigit);
        boolean rightNumeric = right.chars().allMatch(Character::isDigit);

        if (leftNumeric && rightNumeric) {
            return new BigInteger(left).compareTo(new BigInteger(right));
        }
        if (leftNumeric != rightNumeric) {
            return leftNumeric ? 1 : -1;
        }
        return left.compareToIgnoreCase(right);
    }

    private static @NonNull Map<String, Pair<FileJar, HorizonPluginMetadata>> indexByName(
        final @NonNull Set<Pair<FileJar, HorizonPluginMetadata>> input
    ) throws PhaseException {
        final Map<String, Pair<FileJar, HorizonPluginMetadata>> byName = new LinkedHashMap<>();
        for (final Pair<FileJar, HorizonPluginMetadata> pair : input) {
            final String pluginName = pair.b().name();
            if (byName.containsKey(pluginName)) {
                throw new PhaseException("Duplicate plugin name detected: " + pluginName);
            }
            byName.put(pluginName, pair);
        }
        return byName;
    }

    private static boolean validateRuntimeDependencies(
        final @NonNull HorizonPluginMetadata pluginMetadata,
        final @NonNull MinecraftVersion currentVersion,
        final int asmVersion
    ) {
        if (pluginMetadata.dependencies().containsKey("minecraft")) {
            String constraint = pluginMetadata.dependencies().getValueOrThrow("minecraft").asString();
            if (!matches(constraint, currentVersion)) {
                LOGGER.error("Version requirement for plugin {} is not met. Current version, {}, requires, {}",
                    pluginMetadata.name(),
                    currentVersion.getName(),
                    constraint
                );
                return false;
            }
        }

        if (pluginMetadata.dependencies().containsKey("java")) {
            String javaConstraint = pluginMetadata.dependencies()
                .getValueOrThrow("java")
                .asString();

            if (!matchesGenericInteger(javaConstraint, HorizonLoader.JAVA_VERSION)) {
                LOGGER.error(
                    "Java version requirement for plugin {} is not met. Current Java={}, requires={}",
                    pluginMetadata.name(),
                    HorizonLoader.JAVA_VERSION,
                    javaConstraint
                );
                return false;
            }
        }

        if (pluginMetadata.dependencies().containsKey("asm")) {
            String asmConstraint = pluginMetadata.dependencies().getValueOrThrow("asm").asString();
            if (!matchesGenericInteger(asmConstraint, asmVersion)) {
                LOGGER.error(
                    "ASM version requirement for plugin {} is not met. Current ASM={}, requires={}",
                    pluginMetadata.name(),
                    asmVersion,
                    asmConstraint
                );
                return false;
            }
        }
        return true;
    }

    private static @NonNull Map<String, String> getHorizonDependencies(@NonNull HorizonPluginMetadata metadata) {
        final Map<String, String> dependencies = new LinkedHashMap<>();
        final ObjectTree root = metadata.dependencies();

        root.getTreeOptional("horizon").ifPresent(horizonTree -> {
            for (String key : horizonTree.keys()) {
                String dependencyName = key.trim();
                if (dependencyName.isEmpty()) {
                    continue;
                }
                String constraint = horizonTree.getValueSafe(key).asStringOptional().orElse("").trim();
                dependencies.put(dependencyName, constraint);
            }
        });

        for (String key : root.keys()) {
            if (SYSTEM_DEPENDENCY_KEYS.contains(key)) {
                continue;
            }
            String dependencyName = key.trim();
            if (dependencyName.isEmpty()) {
                continue;
            }
            String constraint = root.getValueSafe(key).asStringOptional().orElse("").trim();
            dependencies.putIfAbsent(dependencyName, constraint);
        }
        return dependencies;
    }

    private static boolean validateHorizonDependencies(
        @NonNull HorizonPluginMetadata pluginMetadata,
        @NonNull Map<String, Pair<FileJar, HorizonPluginMetadata>> availablePlugins
    ) {
        for (Map.Entry<String, String> dependency : getHorizonDependencies(pluginMetadata).entrySet()) {
            final String dependencyName = dependency.getKey();
            final String constraint = dependency.getValue();

            if (pluginMetadata.name().equals(dependencyName)) {
                LOGGER.error("Invalid horizon dependency for plugin {}: a plugin cannot depend on itself", pluginMetadata.name());
                return false;
            }

            final Pair<FileJar, HorizonPluginMetadata> resolved = availablePlugins.get(dependencyName);
            if (resolved == null) {
                LOGGER.error(
                    "Horizon dependency requirement for plugin {} is not met. Missing plugin={}, requires={}",
                    pluginMetadata.name(),
                    dependencyName,
                    constraint.isBlank() ? "<present>" : constraint
                );
                return false;
            }

            if (constraint.isBlank()) {
                continue;
            }

            final String dependencyVersion = resolved.b().version();
            if (!matchesLooseVersion(constraint, dependencyVersion)) {
                LOGGER.error(
                    "Horizon dependency version requirement for plugin {} is not met. Dependency={}, currentVersion={}, requires={}",
                    pluginMetadata.name(),
                    dependencyName,
                    dependencyVersion,
                    constraint
                );
                return false;
            }
        }
        return true;
    }

    @Override
    public Set<Pair<FileJar, HorizonPluginMetadata>> execute(final @NonNull Set<Pair<FileJar, HorizonPluginMetadata>> input, final LoadContext context) throws PhaseException {
        final Map<String, Pair<FileJar, HorizonPluginMetadata>> discoveredPlugins = indexByName(input);
        final Map<String, Pair<FileJar, HorizonPluginMetadata>> resolvedPlugins = new LinkedHashMap<>();
        final MinecraftVersion currentVersion = HorizonLoader.getInstance().getVersionMeta().minecraftVersion();
        final int asmVersion = MixinTransformationImpl.ASM_VERSION;

        for (Pair<FileJar, HorizonPluginMetadata> pair : discoveredPlugins.values()) {
            if (validateRuntimeDependencies(pair.b(), currentVersion, asmVersion)) {
                resolvedPlugins.put(pair.b().name(), pair);
            }
        }

        boolean changed;
        do {
            changed = false;
            List<String> toRemove = new ArrayList<>();
            for (Map.Entry<String, Pair<FileJar, HorizonPluginMetadata>> entry : resolvedPlugins.entrySet()) {
                if (!validateHorizonDependencies(entry.getValue().b(), resolvedPlugins)) {
                    toRemove.add(entry.getKey());
                }
            }
            if (!toRemove.isEmpty()) {
                changed = true;
                toRemove.forEach(resolvedPlugins::remove);
            }
        } while (changed);

        return new HashSet<>(resolvedPlugins.values());
    }

    @Override
    public String getName() {
        return "Resolution";
    }
}
