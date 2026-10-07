package nl.pixelretreat.atlas.command;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import nl.pixelretreat.atlas.api.AtlasFlag;
import nl.pixelretreat.atlas.config.AtlasConfig;
import nl.pixelretreat.atlas.message.AtlasMessages;
import nl.pixelretreat.atlas.service.RulesService;
import nl.pixelretreat.atlas.storage.AtlasRepository;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

/** The trading flag flows through the shared flag command, completion, access and info output. */
class AtlasCommandTest {
    @Test void flagCompletionIncludesTradingAndStaysAdminOnly() {
        AtlasCommand command = new AtlasCommand(null, null, services(mock(RulesService.class), null, null));
        CommandSender admin = admin();
        List<String> flags = command.onTabComplete(admin, null, "atlas", new String[]{"flag", "arena", ""});
        assertEquals(Arrays.stream(AtlasFlag.values()).map(AtlasFlag::key).toList(), flags);
        assertTrue(flags.contains("trading"));

        CommandSender staff = mock(CommandSender.class);
        when(staff.hasPermission(AtlasCommand.ADMIN)).thenReturn(false);
        assertTrue(command.onTabComplete(staff, null, "atlas", new String[]{"flag", "arena", ""}).isEmpty());
    }

    @Test void flagCommandRoutesOnOffAndDefaultThroughTheGenericPath() {
        Map<String, Boolean> values = Map.of("on", true, "off", false);
        for (Map.Entry<String, Boolean> value : values.entrySet()) {
            RulesService rules = mock(RulesService.class);
            when(rules.setFlag("atlas:arena", AtlasFlag.TRADING, value.getValue()))
                    .thenReturn(CompletableFuture.completedFuture(null));
            AtlasCommand command = new AtlasCommand(null, mock(AtlasMessages.class), services(rules, null, null));
            command.onCommand(admin(), null, "atlas", new String[]{"flag", "arena", "trading", value.getKey()});
            verify(rules).setFlag("atlas:arena", AtlasFlag.TRADING, value.getValue());
        }

        RulesService rules = mock(RulesService.class);
        when(rules.setFlag("atlas:arena", AtlasFlag.TRADING, null)).thenReturn(CompletableFuture.completedFuture(null));
        AtlasCommand command = new AtlasCommand(null, mock(AtlasMessages.class), services(rules, null, null));
        command.onCommand(admin(), null, "atlas", new String[]{"flag", "arena", "trading", "default"});
        verify(rules).setFlag("atlas:arena", AtlasFlag.TRADING, null);
    }

    @Test void infoOutputListsTradingWithItsEffectiveValue() {
        RulesService rules = new RulesService(mock(Plugin.class), mock(AtlasRepository.class), null, config(true));
        rules.publish(Map.of());
        AtlasMessages messages = mock(AtlasMessages.class);
        Plugin plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        AtlasCommand command = new AtlasCommand(plugin, messages, services(rules, null, null));
        CommandSender sender = admin();
        AtomicReference<Map<String, String>> sent = new AtomicReference<>();
        doAnswer(invocation -> { sent.set(invocation.getArgument(2)); return null; })
                .when(messages).send(eq(sender), eq("info.flags"), anyMap());
        command.onCommand(sender, null, "atlas", new String[]{"info", "arena"});
        assertTrue(sent.get().get("flags").contains("trading=on"));
    }

    @Test void reloadCommandStartsWithoutWaitingAndReportsTheResult() throws Exception {
        CompletableFuture<Void> pending = new CompletableFuture<>();
        AtomicBoolean started = new AtomicBoolean();
        AtlasMessages messages = mock(AtlasMessages.class);
        AtlasCommand command = new AtlasCommand(null, messages,
                services(null, null, () -> { started.set(true); return pending; }));
        CommandSender sender = admin();
        CompletableFuture.runAsync(() -> command.onCommand(sender, null, "atlas", new String[]{"reload"}))
                .get(2, TimeUnit.SECONDS);
        assertTrue(started.get(), "the reload supplier must have been invoked");
        verify(messages, never()).send(eq(sender), anyString());
        pending.complete(null);
        verify(messages).send(sender, "reload.done");

        CompletableFuture<Void> failing = new CompletableFuture<>();
        AtlasMessages failureMessages = mock(AtlasMessages.class);
        AtlasCommand failureCommand = new AtlasCommand(null, failureMessages, services(null, null, () -> failing));
        failureCommand.onCommand(sender, null, "atlas", new String[]{"reload"});
        failing.completeExceptionally(new IllegalStateException("database"));
        verify(failureMessages).send(sender, "reload.failed");
    }

    @Test void deniedUsersCannotInvokeFlagOrReload() {
        RulesService rules = mock(RulesService.class);
        AtomicBoolean started = new AtomicBoolean();
        AtlasMessages messages = mock(AtlasMessages.class);
        AtlasCommand command = new AtlasCommand(null, messages,
                services(rules, null, () -> { started.set(true); return CompletableFuture.completedFuture(null); }));
        CommandSender denied = mock(CommandSender.class);
        when(denied.hasPermission(AtlasCommand.ADMIN)).thenReturn(false);
        command.onCommand(denied, null, "atlas", new String[]{"flag", "arena", "trading", "off"});
        command.onCommand(denied, null, "atlas", new String[]{"reload"});
        verify(rules, never()).setFlag(anyString(), any(AtlasFlag.class), any());
        assertFalse(started.get(), "a denied sender must not start a reload");
        verify(messages, times(2)).send(denied, "common.permission");
    }

    private static AtlasCommand.Services services(RulesService rules,
            java.util.function.Function<org.bukkit.entity.Player, CompletableFuture<nl.pixelretreat.atlas.service.SelectorDeliveryService.Result>> selector,
            java.util.function.Supplier<CompletableFuture<Void>> reload) {
        return new AtlasCommand.Services(null, rules, null, null, null, null, null, selector, reload);
    }

    private static AtlasConfig config(boolean trading) {
        Map<AtlasFlag, Boolean> flags = new EnumMap<>(AtlasFlag.class);
        for (AtlasFlag flag : AtlasFlag.values()) flags.put(flag, flag != AtlasFlag.KEEP_INVENTORY);
        flags.put(AtlasFlag.TRADING, trading);
        return new AtlasConfig("EU", Map.of("EU", "survival", "NA", "survival-na"),
                "127.0.0.1", 3306, "pixelretreat_veyra", "atlas", "", "disable",
                121, 16, 2500, 5, 4096, 1024, flags, 1, 16);
    }

    private static CommandSender admin() {
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission(AtlasCommand.ADMIN)).thenReturn(true);
        return sender;
    }
}
