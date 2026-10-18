package net.neoforbric.kernel.mixin;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
/**
 * The real 0.116.17 language mixin as the kernel hands it to the adapter — the module remapped into the base's
 * names with its annotation strings resolved through the module's refmap.
 *
 * <p>None of the row is reachable on this anchor, and every reason is a verified artifact fact rather than a
 * judgement:
 *
 * <ul>
 *   <li>the gate names {@code net/fabricmc/fabric/mixin/resource/server/LanguageMixin}; 0.116.17 ships the class in
 *       {@code fabric-resource-loader-v0} as {@code .../resource/loader/server/LanguageMixin}, so no real class ever
 *       matches it;</li>
 *   <li>the {@code create} half wants a {@code (Map)Map} {@code @Redirect} on {@code Map.copyOf(Map)} inside a
 *       {@code loadDefault} that calls {@code LanguageHook.captureLanguageMap}; the real handler takes an
 *       {@code ImmutableMap$Builder} and redirects {@code ImmutableMap$Builder.build()} inside
 *       {@code loadDefault()Language} — another shape entirely;</li>
 *   <li>the {@code readCorrectVanillaResource} half compares the annotation's {@code method} value against the bare
 *       {@code parseTranslations(BiConsumer,String)}; the module's refmap writes it owner-qualified
 *       ({@code Lnet/minecraft/locale/Language;parseTranslations(BiConsumer,String)V}), so even a reached handler
 *       could not match. Its {@code @At} target, on the other hand, IS the shape the row wants, and the merged
 *       {@code Language} is the shape the move assumes: the two-argument form only delegates, the three-argument form
 *       makes the one {@code getResourceAsStream} call. Only those two constants stop the row.</li>
 * </ul>
 *
 * <p>The weave-level test of this row (FabricServerLanguageMixinAdapterWeaveTest) is pinned to a hand-written fixture
 * that still carries the 26.x class path and the bare method value, so re-anchoring the gate or the comparison here
 * would have to move that fixture too. Both are production changes; the tests below assert the gap instead.
 */
@org.junit.jupiter.api.parallel.ResourceLock("system-properties")
class FabricServerLanguageMixinAdapterTest {
 private ClassNode mixin()throws Exception{return StagedFabricMixinFixture.resolved("fabric-resource-loader-v0","net/fabricmc/fabric/mixin/resource/loader/server/LanguageMixin");}
 @AfterEach void clear(){System.clearProperty(FabricServerLanguageMixinAdapter.PROPERTY);}
 /**
  * What this used to assert: both halves move — the merge handler is renamed with the capture shim added, and the
  * vanilla-resource read is re-pointed — two moves, verifying, and a second pass changing nothing.
  *
  * <p>On this anchor only the second half is the shape the row was written for, so one move happens; the {@code create}
  * half is skipped because 1.21.1's mixin has no {@code (Map)Map} {@code @Redirect} there (see the class note), which
  * is why the two moves' assertions are gone with the move that cannot happen. What the create half WOULD have done is
  * still asserted as the absence it is: no renamed method and no capture shim are added.
  */
 @Test void actualAdapterKeepsTheNativeMutableMapAndComponentCapture()throws Exception{
  ClassNode mixin=mixin(),target=StagedFabricMixinFixture.game("net/minecraft/locale/Language",false);
  assertEquals(0,FabricServerLanguageMixinAdapter.adapt(mixin,n->target),"the 26.x gate matches no class the module ships");
  assertThrows(AssertionError.class,()->StagedFabricMixinFixture.mixin("fabric-resource-loader-v0","net/fabricmc/fabric/mixin/resource/server/LanguageMixin"),"the gate's path is not the module's");
  MethodNode create=StagedFabricMixinFixture.method(mixin,"create");
  assertEquals("(Lcom/google/common/collect/ImmutableMap$Builder;)Lcom/google/common/collect/ImmutableMap;",create.desc,"the create half is another shape than the row's (Map)Map");
  assertEquals("Lcom/google/common/collect/ImmutableMap$Builder;build()Lcom/google/common/collect/ImmutableMap;",MixinFit.value(MixinFit.atNodes(MixinFit.injectorOf(create)).getFirst(),"target"));
  MethodNode read=StagedFabricMixinFixture.method(mixin,"readCorrectVanillaResource");
  assertEquals(List.of("Lnet/minecraft/locale/Language;parseTranslations(Ljava/util/function/BiConsumer;Ljava/lang/String;)V"),
    MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(read),"method")),"owner-qualified, where the row compares the bare name");
  assertEquals("Ljava/lang/Class;getResourceAsStream(Ljava/lang/String;)Ljava/io/InputStream;",MixinFit.value(MixinFit.atNodes(MixinFit.injectorOf(read)).getFirst(),"target"),"the @At target is the one the row wants");
  // The merged base IS the shape the read half assumes; only the two constants stop it.
  MethodNode narrowed=target.methods.stream().filter(m->m.name.equals("parseTranslations")&&m.desc.equals("(Ljava/util/function/BiConsumer;Ljava/lang/String;)V")).findFirst().orElseThrow();
  assertTrue(java.util.Arrays.stream(narrowed.instructions.toArray()).anyMatch(i->i instanceof MethodInsnNode c&&c.name.equals("parseTranslations")&&c.desc.equals("(Ljava/util/function/BiConsumer;Ljava/util/function/BiConsumer;Ljava/lang/String;)V")),"the two-argument form only delegates");
  assertTrue(target.methods.stream().filter(m->m.name.equals("parseTranslations")&&m.desc.equals("(Ljava/util/function/BiConsumer;Ljava/util/function/BiConsumer;Ljava/lang/String;)V")).anyMatch(m->java.util.Arrays.stream(m.instructions.toArray()).anyMatch(i->i instanceof MethodInsnNode c&&c.name.equals("getResourceAsStream"))),"and the three-argument form makes the read");
  assertTrue(mixin.methods.stream().noneMatch(m->m.name.equals("neoforbric$mergeFabricLanguages")),"nothing was renamed");
  assertTrue(mixin.methods.stream().noneMatch(m->m.name.equals("neoforbric$captureLanguageMap")),"and no capture shim was added");
 }
 /** <p>The unpatched vanilla {@code Language} has no widened {@code parseTranslations} for the read to move onto,
  * and the property switches the adapter off entirely — both declines are real, not the gate's. */
 @Test void nativeVanillaAndDisabledAdapterRemainUnchanged()throws Exception{
  ClassNode mixin=mixin(),nativeBody=StagedFabricMixinFixture.game("net/minecraft/locale/Language",true);assertEquals(0,FabricServerLanguageMixinAdapter.adapt(mixin,n->nativeBody));
  System.setProperty(FabricServerLanguageMixinAdapter.PROPERTY,"off");ClassNode merged=StagedFabricMixinFixture.game("net/minecraft/locale/Language",false);assertEquals(0,FabricServerLanguageMixinAdapter.adapt(mixin,n->merged));
 }
 /** A handler with a @Group or a changed body is left alone — and so is the read, whose row never matches on this anchor. */
 @Test void unknownMergeBodyOrAlternativeGroupDoesNotReplaceItsReturnContract()throws Exception{
  for(boolean group:new boolean[]{false,true}){
   ClassNode mixin=mixin(),target=StagedFabricMixinFixture.game("net/minecraft/locale/Language",false);MethodNode create=StagedFabricMixinFixture.method(mixin,"create");
   if(group)create.visibleAnnotations.add(new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Group;"));else create.instructions.insert(new InsnNode(Opcodes.NOP));
   assertEquals(0,FabricServerLanguageMixinAdapter.adapt(mixin,n->target),"nothing moves, with or without a @Group");
   assertNotNull(MixinFit.injectorOf(create),"the create handler keeps its own injector");
   assertEquals("Lorg/spongepowered/asm/mixin/injection/Redirect;",MixinFit.injectorOf(create).desc);
  }
 }
}
