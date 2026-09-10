package com.sablednah.cast.neoforge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.sablednah.cast.CastMod;

import net.neoforged.fml.loading.FMLPaths;

/**
 * Every player-facing string, owner-editable in {@code config/cast/messages.yml}
 * and merged on every start via {@code messages.known} (the family's Lang:
 * a vanilla client carries no lang file of ours, so the server resolves text).
 */
public final class Lang {

    private static final Map<String, String> DEFAULTS = new LinkedHashMap<>();
    private static Map<String, String> active = new LinkedHashMap<>();

    static String def(String key, String template) {
        DEFAULTS.put(key, template);
        return key;
    }

    static {
        def("prefix", "&6[Cast]&r ");
        def("msg.say", "&e{name}&7: &f{text}");
        def("msg.spawned", "{prefix}&7Placed &f{name}&7 ({kind}) as &f{id}&7.");
        def("msg.spawned.brain", "{prefix}&7That body is brain-driven; its behaviours are removed each second and it will only look about. A human or a goal mob moves more naturally.");
        def("msg.spawned.unknown", "{prefix}&cNo such entity type: {type}");
        def("msg.spawned.not_mob", "{prefix}&cThat entity is not a creature and cannot be a body.");
        def("msg.removed", "{prefix}&7Removed &f{name}&7.");
        def("msg.none_here", "{prefix}&7Look at an NPC within reach, or stand next to one.");
        def("msg.list.header", "{prefix}&f{count} NPC(s):");
        def("msg.list.entry", "  &7{kind} &f{name} &7at {x}, {y}, {z} in {dim} &8{roles}");
        def("msg.list.none", "{prefix}&7No NPCs yet. /cast spawn human <name> [skin] puts one where you look.");
        def("msg.skin", "{prefix}&7Skin of &f{account}&7 requested for &f{name}&7; it lands when Mojang answers.");
        def("msg.skin.off", "{prefix}&7Skin fetching is off in config/cast; the NPC keeps the default skin.");
        def("msg.named", "{prefix}&7Renamed to &f{name}&7.");
        def("msg.equip.done", "{prefix}&7{name} now has &f{item}&7 in {slot}.");
        def("msg.equip.cleared", "{prefix}&7{name}'s {slot} is empty.");
        def("msg.equip.bad", "{prefix}&cNo. Slots are mainhand, offhand, head, chest, legs, feet; the item is written as /give takes it. Got '{slot}' and '{item}'.");
        def("msg.named.long", "{prefix}&7Note: a human's name tag shows the first 16 characters.");
        def("msg.look", "{prefix}&7Looking at players: &f{value}&7.");
        def("msg.gravity.defied", "{prefix}&7{name} now defies gravity. Mine away.");
        def("msg.gravity.obeyed", "{prefix}&7{name} obeys gravity again, and will land where the ground is.");
        def("msg.role.added", "{prefix}&7Role &f{role}&7 added.");
        def("msg.role.removed", "{prefix}&7Role &f{role}&7 removed.");
        def("msg.role.unknown", "{prefix}&7Nothing has registered a role called &f{role}&7 (known: {known}). Added anyway; it will act once something does.");
        def("msg.moved", "{prefix}&7Brought &f{name}&7 here.");
        def("msg.status", "{prefix}&f{count} NPC(s), {loaded} human phantom(s) loaded, roles: {roles}");
        def("msg.status.build", "&7Build: &f{build}");
    }

    public static String get(String key) {
        String template = active.getOrDefault(key, DEFAULTS.get(key));
        if (template == null) {
            CastMod.LOGGER.warn("Missing message key '{}'", key);
            return key;
        }
        return template.contains("{prefix}") ? template.replace("{prefix}", active.getOrDefault("prefix", DEFAULTS.get("prefix"))) : template;
    }

    public static String fmt(String key, Object... kv) {
        String out = get(key);
        for (int n = 0; n + 1 < kv.length; n += 2) out = out.replace("{" + kv[n] + "}", String.valueOf(kv[n + 1]));
        return out;
    }

    public static int catalogueSize() {
        return DEFAULTS.size();
    }

    private static Path dir() { return FMLPaths.CONFIGDIR.get().resolve(CastMod.MODID); }

    public static synchronized void load() {
        Path path = dir().resolve("messages.yml");
        Path known = dir().resolve("messages.known");
        try {
            Files.createDirectories(path.getParent());
            if (!Files.exists(path)) {
                StringBuilder sb = new StringBuilder("# Cast messages -- every player-facing string. '&' colour codes work.\n# Deleted keys fall back to defaults; new keys are appended once, then left alone.\n\n");
                DEFAULTS.forEach((k, v) -> sb.append(line(k, v)));
                Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
            } else {
                Set<String> seen = Files.exists(known)
                        ? new LinkedHashSet<>(Files.readAllLines(known, StandardCharsets.UTF_8)) : Set.of();
                Set<String> present = new LinkedHashSet<>();
                Object parsed = new org.yaml.snakeyaml.Yaml().load(Files.readString(path, StandardCharsets.UTF_8));
                if (parsed instanceof Map<?, ?> map) map.keySet().forEach(k -> present.add(String.valueOf(k)));
                StringBuilder add = new StringBuilder();
                DEFAULTS.forEach((k, v) -> { if (!seen.contains(k) && !present.contains(k)) add.append(line(k, v)); });
                if (!add.isEmpty()) Files.writeString(path, Files.readString(path, StandardCharsets.UTF_8) + "\n# --- new keys ---\n" + add, StandardCharsets.UTF_8);
            }
            Map<String, String> loaded = new LinkedHashMap<>();
            Object parsed = new org.yaml.snakeyaml.Yaml().load(Files.readString(path, StandardCharsets.UTF_8));
            if (parsed instanceof Map<?, ?> map) map.forEach((k, v) -> { if (k != null && v != null) loaded.put(String.valueOf(k), String.valueOf(v)); });
            active = loaded;
            Files.writeString(known, String.join("\n", DEFAULTS.keySet()) + "\n", StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            CastMod.LOGGER.error("Cast: could not load messages.yml -- using defaults", e);
            active = new LinkedHashMap<>();
        }
    }

    private static String line(String k, String v) {
        return k + ": \"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"\n";
    }

    private Lang() {}
}
