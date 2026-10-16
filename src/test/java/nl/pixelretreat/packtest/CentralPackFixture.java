package nl.pixelretreat.packtest;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.plugin.Plugin;
import nl.pixelretreat.campfire.api.*;
import nl.pixelretreat.closet.api.*;
import nl.pixelretreat.closet.service.DefaultClosetService;
import static org.mockito.Mockito.*;
/** Real definitions-only registrations against a sole Closet owner using verified central source assets. */
public final class CentralPackFixture {
 private static final Map<DefaultClosetService,Plugin> owners=Collections.synchronizedMap(new WeakHashMap<>());
 private CentralPackFixture() {}
 public static Plugin owner(DefaultClosetService service) { return Objects.requireNonNull(owners.get(service)); }
 public static DefaultClosetService service(Plugin consumer,dev.veyra.api.VeyraCluster cluster,CampfireRuntime runtime,ExecutorService worker) {
  Plugin source="Closet".equals(consumer.getName())?consumer:mock(Plugin.class);when(source.getName()).thenReturn("Closet");when(source.isEnabled()).thenReturn(true);
  Path root=Path.of(System.getProperty("closetTestSource"));when(source.getDataFolder()).thenReturn(root.toFile());
  var catalog=runtime.localization().register(consumer).toCompletableFuture().join();
  var sharedRuntime=mock(CampfireRuntime.class);var registry=mock(CampfireLocalization.class);
  when(sharedRuntime.localization()).thenReturn(registry);
  when(registry.register(any())).thenAnswer(call->call.getArgument(0)==source?CompletableFuture.completedFuture(catalog):runtime.localization().register(call.getArgument(0)));
  var service=new DefaultClosetService(source,cluster,sharedRuntime,worker);owners.put(service,source);
  service.register(source,new ContentContribution(List.of(),sharedGlyphs(root),root.resolve("pack"))).toCompletableFuture().join();
  return service;
 }
 /** Use the real public source registrations, without parsing unrelated item/material definitions. */
 private static List<GlyphDefinition> sharedGlyphs(Path root) {
  try {
   Map<?,?> manifest=new org.yaml.snakeyaml.Yaml().load(Files.readString(root.resolve("closet.yml")));
   Map<?,?> definitions=(Map<?,?>)Objects.requireNonNull(manifest.get("glyphs"));
   var result=new ArrayList<GlyphDefinition>();
   definitions.forEach((id,value)->{
    if(id.toString().startsWith("closet:lore/")) {
     Map<?,?> glyph=(Map<?,?>)value;
     result.add(new GlyphDefinition(ItemId.parse(id.toString()),ItemId.parse(glyph.get("font").toString()),
      ((Number)glyph.get("codepoint")).intValue(),((Number)glyph.get("width")).intValue(),
      ((Number)glyph.get("height")).intValue(),((Number)glyph.get("ascent")).intValue()));
    }
   });
   return List.copyOf(result);
  } catch(java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
 }

}
