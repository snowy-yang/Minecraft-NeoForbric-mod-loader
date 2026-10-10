package net.neoforbric.kernel.mapping;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * The refmap rewrite half of a namespace-crossing module remap. The scenarios are the real shapes loom wrote
 * into fabric-loot-api-v3's refmap for 1.21.1 — a member with its owner, an owner-less field, a bare method
 * name, the @At target a MixinExtras wrap anchors by, a legacy {@code data} section — plus everything the
 * merged tree does not know, which must pass through untouched (a mod's own names never were intermediary).
 *
 * <p>The indexes are filled directly instead of built from a merged tree: the tree needs this machine's
 * staged mapping files, and the shapes under test are the rewriter's own, not the tree's.
 */
class RefmapRemapperTest {
	private RefmapRemapper.Names names() {
		RefmapRemapper.Names names = new RefmapRemapper.Names();
		names.classByIntermediary.put("net/minecraft/class_9383", "net/minecraft/server/ReloadableServerRegistries");
		names.classByIntermediary.put("net/minecraft/class_9383$class_9384",
				"net/minecraft/server/ReloadableServerRegistries$EmptyTagLookupWrapper");
		names.classByIntermediary.put("net/minecraft/class_1799", "net/minecraft/world/item/ItemStack");
		names.classByIntermediary.put("net/minecraft/class_2960", "net/minecraft/resources/ResourceLocation");
		names.methodByIntermediaryName.put("method_58278", "lambda$scheduleElementParse$3");
		names.methodByIntermediaryName.put("method_58284", "reload");
		names.methodByIntermediaryNameDesc.put("method_58278(Lnet/minecraft/class_9383$class_1;)V",
				"lambda$scheduleElementParse$3");
		names.methodByIntermediaryNameDesc.put("method_58278(Lnet/minecraft/class_8490;Lnet/minecraft/class_6903;Lnet/minecraft/class_2385;Lnet/minecraft/class_2960;Lcom/google/gson/JsonElement;)V",
				"lambda$scheduleElementParse$3");
		names.methodByIntermediaryNameDesc.put("method_57093(Lcom/mojang/serialization/DynamicOps;)Lnet/minecraft/class_6903;",
				"createSerializationContext");
		names.fieldByIntermediaryName.put("field_7905", "fluid");
		names.fieldByIntermediaryNameDesc.put("field_7905:Lnet/minecraft/class_3611;", "fluid");
		return names;
	}

	@Test
	void aMemberWithItsOwnerResolvesThroughTheMethodIndex() {
		RefmapRemapper.Names names = names();
		// fabric-loot-api-v3's ReloadableRegistriesMixin, the wrap target of modifyLootTable.
		// class_2960 is in the class index, so the descriptor translates it; class_8490/class_6903 are
		// deliberately absent from this test's index and pass through — a tree always knows them, but the
		// rewriter must not drop what it cannot name.
		assertEquals("Lnet/minecraft/server/ReloadableServerRegistries;lambda$scheduleElementParse$3"
				+ "(Lnet/minecraft/class_8490;Lnet/minecraft/class_6903;Lnet/minecraft/class_2385;"
				+ "Lnet/minecraft/resources/ResourceLocation;Lcom/google/gson/JsonElement;)V",
				RefmapRemapper.remapValue("Lnet/minecraft/class_9383;method_58278(Lnet/minecraft/class_8490;"
						+ "Lnet/minecraft/class_6903;Lnet/minecraft/class_2385;Lnet/minecraft/class_2960;"
						+ "Lcom/google/gson/JsonElement;)V", names));
	}

	@Test
	void anAtTargetResolvesToTheMojmapCallSite() {
		// The INVOKE target storeOps wraps: yarn names in the class bytes, resolved by the refmap key.
		assertEquals("Lnet/minecraft/server/ReloadableServerRegistries$EmptyTagLookupWrapper;"
				+ "createSerializationContext(Lcom/mojang/serialization/DynamicOps;)Lnet/minecraft/class_6903;",
				RefmapRemapper.remapValue("Lnet/minecraft/class_9383$class_9384;method_57093"
						+ "(Lcom/mojang/serialization/DynamicOps;)Lnet/minecraft/class_6903;", names()));
	}

	@Test
	void aFieldFormResolvesWithoutAnOwner() {
		// BucketItemAccessor's shadow: the "name:descriptor" form loom writes for fields.
		assertEquals("fluid:Lnet/minecraft/class_3611;",
				RefmapRemapper.remapValue("field_7905:Lnet/minecraft/class_3611;", names()));
	}

	@Test
	void bareNamesAndClassesResolveByGlobalUniqueness() {
		RefmapRemapper.Names names = names();
		assertEquals("reload", RefmapRemapper.remapValue("method_58284", names));
		assertEquals("fluid", RefmapRemapper.remapValue("field_7905", names));
		assertEquals("net/minecraft/server/ReloadableServerRegistries", RefmapRemapper.remapValue("net/minecraft/class_9383", names));
		assertEquals("Lnet/minecraft/server/ReloadableServerRegistries;", RefmapRemapper.remapValue("Lnet/minecraft/class_9383;", names));
	}

	@Test
	void unknownNamesPassThroughUntouched() {
		RefmapRemapper.Names names = names();
		// A mod's own member, an already-Named name, and a non-Minecraft library call.
		assertEquals("Lcom/example/Mod;someMethod()V", RefmapRemapper.remapValue("Lcom/example/Mod;someMethod()V", names));
		assertEquals("Ljava/util/concurrent/CompletableFuture;thenApplyAsync(Ljava/util/function/Function;"
				+ "Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;",
				RefmapRemapper.remapValue("Ljava/util/concurrent/CompletableFuture;thenApplyAsync"
						+ "(Ljava/util/function/Function;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;", names));
		assertEquals("alreadyNamed", RefmapRemapper.remapValue("alreadyNamed", names));
		assertEquals("", RefmapRemapper.remapValue("", names));
		assertNull(RefmapRemapper.remapValue(null, names));
	}

	@Test
	void descriptorsTranslateEveryClassTheyName() {
		RefmapRemapper.Names names = names();
		assertEquals("(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/resources/ResourceLocation;)V",
				RefmapRemapper.remapDescriptor("(Lnet/minecraft/class_1799;Lnet/minecraft/class_2960;)V", names));
		// Arrays keep their brackets; primitives keep their shape; unknowns keep their name.
		assertEquals("[[Lnet/minecraft/world/item/ItemStack;I[Z", RefmapRemapper.remapDescriptor(
				"[[Lnet/minecraft/class_1799;I[Z", names));
		assertEquals("()V", RefmapRemapper.remapDescriptor("()V", names));
		assertEquals("(Lcom/example/Thing;)V", RefmapRemapper.remapDescriptor("(Lcom/example/Thing;)V", names));
		// A malformed trailing L (no ';') must survive verbatim, not loop or eat the tail.
		assertEquals("(Lbroken", RefmapRemapper.remapDescriptor("(Lbroken", names));
	}

	@Test
	void aRefmapJsonRewritesBothLayoutsAndKeepsItsKeys() {
		// Both loom layouts at once: "mappings" and the legacy "data" with a namespace level inside.
		byte[] json = ("{\"mappings\":{\"net/fabricmc/fabric/mixin/loot/ReloadableRegistriesMixin\":"
				+ "{\"method_58278\":\"Lnet/minecraft/class_9383;method_58278(Lnet/minecraft/class_8490;"
				+ "Lnet/minecraft/class_6903;Lnet/minecraft/class_2385;Lnet/minecraft/class_2960;"
				+ "Lcom/google/gson/JsonElement;)V\",\"reload\":\"Lnet/minecraft/class_9383;method_58284"
				+ "(Lnet/minecraft/class_7780;Lnet/minecraft/class_3300;Ljava/util/concurrent/Executor;)"
				+ "Ljava/util/concurrent/CompletableFuture;\"}},"
				+ "\"data\":{\"named:intermediary\":{\"net/fabricmc/fabric/mixin/transfer/BucketItemAccessor\":"
				+ "{\"fluid\":\"field_7905:Lnet/minecraft/class_3611;\"}}}}").getBytes(StandardCharsets.UTF_8);
		String rewritten = new String(RefmapRemapper.rewrite(json, names()), StandardCharsets.UTF_8);
		assertTrue(rewritten.contains("\"method_58278\""), "the annotation-string key must not change: " + rewritten);
		assertTrue(rewritten.contains("lambda$scheduleElementParse$3"), "the value must be the Mojmap method: " + rewritten);
		assertTrue(rewritten.contains("\"named:intermediary\""), "the legacy data section's namespace level must survive: " + rewritten);
		assertTrue(rewritten.contains("\"fluid\""),
				"the data section's keys survive: " + rewritten);
		assertTrue(rewritten.contains("fluid:Lnet/minecraft/class_3611;"),
				"the data section's values are rewritten too: " + rewritten);
		// A key the mixin's class bytes still spell verbatim.
		assertTrue(rewritten.contains("\"reload\""), "keys pass through: " + rewritten);
	}

	@Test
	void aMixinConfigNamesItsRefmapWhateverTheFileIsCalled() {
		assertEquals("custom-refmap.json", RefmapRemapper.refmapNameOf(
				"{\"required\":true,\"refmap\":\"custom-refmap.json\",\"mixins\":[]}".getBytes(StandardCharsets.UTF_8)));
		assertNull(RefmapRemapper.refmapNameOf("{\"refmap\":true}".getBytes(StandardCharsets.UTF_8)),
				"the pre-0.16 form means \"no refmap\"");
		assertNull(RefmapRemapper.refmapNameOf("{\"some\":[\"not\",\"a\",\"mixin\",\"config\"]}".getBytes(StandardCharsets.UTF_8)));
		assertNull(RefmapRemapper.refmapNameOf("not json at all".getBytes(StandardCharsets.UTF_8)));
	}
}
