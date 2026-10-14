package net.neoforbric.kernel.mixin;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
@org.junit.jupiter.api.parallel.ResourceLock("system-properties")
class FabricClientMixinAnchorsTest {
 private static final String CHUNK="net/minecraft/world/level/chunk/LevelChunk",RENDER="net/minecraft/client/renderer/LevelRenderer";
 // The adapter judges the module as the runtime hands it: remapped to the base's Mojmap names, with the
 // Yarn-authored selector strings Mixin resolves through the refmap. Upstream renamed every one of these
 // mixins for 1.21.1: LevelChunkMixin is client/server WorldChunkMixin, and renderer-api, screen-api and
 // item-api carry no LevelRendererMixin, GuiMixin or MultiPlayerGameModeMixin at all.
 private ClassNode lifecycle()throws Exception{return StagedFabricMixinFixture.remapped("fabric-lifecycle-events-v1","net/fabricmc/fabric/mixin/event/lifecycle/client/WorldChunkMixin");}
 private ClassNode serverLifecycle()throws Exception{return StagedFabricMixinFixture.remapped("fabric-lifecycle-events-v1","net/fabricmc/fabric/mixin/event/lifecycle/server/WorldChunkMixin");}
 private ClassNode renderer()throws Exception{return StagedFabricMixinFixture.remapped("fabric-renderer-api-v1","net/fabricmc/fabric/mixin/renderer/client/WeightedBakedModelMixin");}
 @AfterEach void clear(){System.clearProperty(FabricClientMixinAnchors.PROPERTY);System.clearProperty(net.neoforbric.kernel.transform.FabricItemContractTransformer.PROPERTY);}
 @Test void removedBlockEntityTargetsItsActualMapAndNeverThePendingNbtMap()throws Exception{
  ClassNode mixin=lifecycle(),target=StagedFabricMixinFixture.game(CHUNK,false);assertEquals(1,FabricClientMixinAnchors.adapt(mixin,n->target));
  MethodNode method=mixin.methods.stream().filter(m->m.name.equals("onRemoveBlockEntity")&&m.desc.equals("(Ljava/util/Map;Ljava/lang/Object;)Ljava/lang/Object;")).findFirst().orElseThrow();AnnotationNode inject=MixinFit.injectorOf(method);
  assertNull(MixinFit.value(inject,"slice"));assertEquals(0,MixinFit.value(MixinFit.atNodes(inject).getFirst(),"ordinal"));assertEquals(0,FabricClientMixinAnchors.adapt(mixin,n->target));
 }
 @Test void actualDedicatedServerRemovalUsesTheSameProvenMapWithoutChangingItsCallbackBody()throws Exception{
  ClassNode mixin=serverLifecycle(),target=StagedFabricMixinFixture.game(CHUNK,false);
  MethodNode handler=mixin.methods.stream().filter(m->m.name.equals("onRemoveBlockEntity")&&m.desc.equals("(Ljava/util/Map;Ljava/lang/Object;)Ljava/lang/Object;")).findFirst().orElseThrow();String body=MixinInstructionFingerprint.hash(handler);
  assertEquals(1,FabricClientMixinAnchors.adapt(mixin,n->target));AnnotationNode inject=MixinFit.injectorOf(handler);assertNull(MixinFit.value(inject,"slice"));assertEquals(0,MixinFit.value(MixinFit.atNodes(inject).getFirst(),"ordinal"));assertEquals(body,MixinInstructionFingerprint.hash(handler));
  assertEquals(0,FabricClientMixinAnchors.adapt(mixin,n->target));
 }
 @Test void wrongMapOrCallbackGroupCannotBorrowTheRemovalAnchor()throws Exception{
  ClassNode target=StagedFabricMixinFixture.game(CHUNK,false);for(MethodNode m:target.methods)if(m.name.equals("getBlockEntity"))for(var i:m.instructions)if(i instanceof FieldInsnNode f&&f.name.equals("blockEntities"))f.name="unprovedMap";
  assertEquals(0,FabricClientMixinAnchors.adapt(lifecycle(),n->target));
  ClassNode mixin=lifecycle(),real=StagedFabricMixinFixture.game(CHUNK,false);mixin.methods.stream().filter(m->m.name.equals("onRemoveBlockEntity")&&m.desc.equals("(Ljava/util/Map;Ljava/lang/Object;)Ljava/lang/Object;")).findFirst().orElseThrow().visibleAnnotations.add(new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Group;"));assertEquals(0,FabricClientMixinAnchors.adapt(mixin,n->real));
 }
 @Test void onlyTheActualNoopRedirectCanSuppressTheContextExpandedRenderingCall()throws Exception{
  // fabric-api 0.116.17's renderer-api ships no LevelRendererMixin (model mixins only: BakedModel,
  // Multipart, Weighted, SpriteAtlasTexture, DebugHud), so the render branch has nothing to judge and
  // every renderer-api mixin passes through unchanged — including with the switch off.
  ClassNode mixin=renderer(),target=StagedFabricMixinFixture.game(RENDER,false);
  assertEquals(0,FabricClientMixinAnchors.adapt(mixin,n->target));
  System.setProperty(FabricClientMixinAnchors.PROPERTY,"off");
  assertEquals(0,FabricClientMixinAnchors.adapt(renderer(),n->target));
 }
 @Test void theActualMiningHandlerHasOneNativeDecisionAndOneExplicitFabricFallback()throws Exception{
  ClassNode mixin=StagedFabricMixinFixture.remapped("fabric-item-api-v1","net/fabricmc/fabric/mixin/item/client/ClientPlayerInteractionManagerMixin"),target=StagedFabricMixinFixture.game("net/minecraft/client/multiplayer/MultiPlayerGameMode",false);
  assertEquals(1,FabricMiningMixinAdapter.adapt(mixin,n->target));MethodNode handler=StagedFabricMixinFixture.method(mixin,"fabricItemContinueBlockBreakingInject");int nativeCalls=0,fabricCalls=0;
  for(var i:handler.instructions)if(i instanceof MethodInsnNode c){if(c.name.equals("shouldCauseBlockBreakReset"))nativeCalls++;if(c.name.equals("allowContinuingBlockBreaking"))fabricCalls++;}
  assertEquals(1,nativeCalls);assertEquals(1,fabricCalls);new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.BasicVerifier()).analyze(mixin.name,handler);
  assertEquals(0,FabricMiningMixinAdapter.adapt(mixin,n->target));
 }
 private ClassNode screens()throws Exception{return StagedFabricMixinFixture.remapped("fabric-screen-api-v1","net/fabricmc/fabric/mixin/screen/ScreenMixin");}
 @Test void screenExtractEventsBracketNeoForgesScreenStackCall()throws Exception{
  // fabric-api 0.116.17's ScreenMixin hooks init/resize only — no onExtractGui wrap — and the merged
  // 1.21.1 Gui draws immediate-mode (render(GuiGraphics, DeltaTracker), no extractRenderState, no
  // ClientHooks.extractScreen), so the screen-extract branch has nothing to judge: unchanged, twice.
  ClassNode mixin=screens(),gui=StagedFabricMixinFixture.game("net/minecraft/client/gui/Gui",false);
  assertEquals(0,FabricClientMixinAnchors.adapt(mixin,n->gui));
  assertEquals(0,FabricClientMixinAnchors.adapt(mixin,n->gui),"second adaptation is a no-op");
 }
 @Test void screenExtractIsLeftAloneWhereFabricsOwnAnchorExistsOrTheHandlerChanged()throws Exception{
  // The vanilla Gui (no NeoForge call either) likewise leaves ScreenMixin alone, and a ScreenMixin whose
  // handler changed shape is still not an extract wrap: the branch keys on the handler, not the host.
  ClassNode vanilla=StagedFabricMixinFixture.vanilla("net/minecraft/client/gui/Gui");assertEquals(0,FabricClientMixinAnchors.adapt(screens(),n->vanilla));
  ClassNode gui=StagedFabricMixinFixture.game("net/minecraft/client/gui/Gui",false),changed=screens();
  StagedFabricMixinFixture.method(changed,"beforeInitScreen").instructions.insert(new InsnNode(Opcodes.NOP));
  assertEquals(0,FabricClientMixinAnchors.adapt(changed,n->gui),"not an extract wrap, before or after the edit");
 }
 @Test void nativeVanillaAndDisabledClientAdaptersRemainUnchanged()throws Exception{
	ClassNode vanillaChunk=StagedFabricMixinFixture.vanilla(CHUNK);assertEquals(0,FabricClientMixinAnchors.adapt(lifecycle(),n->vanillaChunk));
  ClassNode vanilla=StagedFabricMixinFixture.vanilla(RENDER);assertEquals(0,FabricClientMixinAnchors.adapt(renderer(),n->vanilla));
  System.setProperty(FabricClientMixinAnchors.PROPERTY,"off");ClassNode target=StagedFabricMixinFixture.game(RENDER,false);assertEquals(0,FabricClientMixinAnchors.adapt(renderer(),n->target));
  ClassNode mining=StagedFabricMixinFixture.remapped("fabric-item-api-v1","net/fabricmc/fabric/mixin/item/client/ClientPlayerInteractionManagerMixin");System.setProperty(net.neoforbric.kernel.transform.FabricItemContractTransformer.PROPERTY,"off");assertEquals(0,FabricMiningMixinAdapter.adapt(mining,n->target));
 }

 private static final String SCREEN_DRAW="Lnet/minecraft/client/gui/screens/Screen;extractRenderStateWithTooltipAndSubtitles(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V";
 private static final String STACK_DRAW="Lnet/neoforged/neoforge/client/ClientHooks;extractScreen(Lnet/minecraft/client/gui/screens/Screen;Ljava/util/Stack;Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V";
 /** A mixin on Gui in LiquidBounce's shape: one {@code @Inject} (or {@code kind}) at the screen draw, {@code shift} AFTER/BEFORE/none. */
 private static ClassNode drawHook(String name,String kind,String shift,net.neoforbric.api.Ecosystem owner){
  ClassNode mixin=new ClassNode();mixin.version=Opcodes.V21;mixin.access=Opcodes.ACC_PUBLIC|Opcodes.ACC_ABSTRACT;mixin.name="test/screen/"+name;mixin.superName="java/lang/Object";
  AnnotationNode target=new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");target.values=new ArrayList<>(List.of("value",new ArrayList<>(List.of(Type.getObjectType("net/minecraft/client/gui/Gui")))));
  mixin.invisibleAnnotations=new ArrayList<>(List.of(target));
  MethodNode handler=new MethodNode(Opcodes.ACC_PRIVATE,"hookScreenRender","(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V",null,null);handler.instructions.add(new InsnNode(Opcodes.RETURN));handler.maxLocals=2;
  AnnotationNode at=new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");at.values=new ArrayList<>(List.of("value","INVOKE","target",SCREEN_DRAW));
  if(shift!=null){at.values.add("shift");at.values.add(new String[]{"Lorg/spongepowered/asm/mixin/injection/At$Shift;",shift});}
  AnnotationNode inject=new AnnotationNode(kind);inject.values=new ArrayList<>(List.of("method",new ArrayList<>(List.of("extractRenderState")),"at",new ArrayList<>(List.of(at))));
  handler.visibleAnnotations=new ArrayList<>(List.of(inject));mixin.methods.add(handler);
  if(owner!=null)MixinStubRebind.noteEcosystem(mixin.name,owner);
  return mixin;
 }
 /** The merged 1.21.1 Gui has no extractRenderState host (immediate-mode render), so a Fabric
  * callback at the 26.x screen-draw point matches no host method and stays where it was written —
  * for every shift position — and the second pass is a no-op. */
 @Test void aFabricCallbackAtTheScreenDrawStandsAtNeoForgesScreenStackCall()throws Exception{
  ClassNode gui=StagedFabricMixinFixture.game("net/minecraft/client/gui/Gui",false);
  for(String shift:Arrays.asList("AFTER","BEFORE",null)){
   ClassNode mixin=drawHook("Draw"+shift,"Lorg/spongepowered/asm/mixin/injection/Inject;",shift,net.neoforbric.api.Ecosystem.FABRIC);
   assertEquals(0,FabricClientMixinAnchors.adapt(mixin,n->gui),String.valueOf(shift));
   AnnotationNode at=StagedFabricMixinFixture.at(mixin,"hookScreenRender");
   assertEquals(SCREEN_DRAW,MixinFit.value(at,"target"),"the point is kept, not moved");
   assertEquals(0,FabricClientMixinAnchors.adapt(mixin,n->gui),"second adaptation is a no-op");
  }
  MixinStubRebind.forget();
 }
 @Test void theScreenDrawRuleLeavesWrapsOtherFamiliesAndAVanillaHostAlone()throws Exception{
  ClassNode merged=StagedFabricMixinFixture.game("net/minecraft/client/gui/Gui",false),vanilla=StagedFabricMixinFixture.vanilla("net/minecraft/client/gui/Gui");
  // A wrap or a redirect replaces the call: its handler is shaped by the call, which the absent host is not.
  for(String kind:List.of("Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;","Lorg/spongepowered/asm/mixin/injection/Redirect;")){
   ClassNode mixin=drawHook("Wrap"+kind.length(),kind,null,net.neoforbric.api.Ecosystem.FABRIC);assertEquals(0,FabricClientMixinAnchors.adapt(mixin,n->merged),kind);
   assertEquals(SCREEN_DRAW,MixinFit.value(StagedFabricMixinFixture.at(mixin,"hookScreenRender"),"target"));
  }
  // A NeoForge mod, an unknown owner, and the switch-off all stay; where the host still lacks the
  // 26.x call the injector binds as written.
  for(net.neoforbric.api.Ecosystem owner:Arrays.asList(net.neoforbric.api.Ecosystem.NEOFORGE,net.neoforbric.api.Ecosystem.NEOFORGE,null)){
   ClassNode mixin=drawHook("Owner"+owner,"Lorg/spongepowered/asm/mixin/injection/Inject;","AFTER",owner);assertEquals(0,FabricClientMixinAnchors.adapt(mixin,n->merged),String.valueOf(owner));
  }
  assertEquals(0,FabricClientMixinAnchors.adapt(drawHook("Vanilla","Lorg/spongepowered/asm/mixin/injection/Inject;","AFTER",net.neoforbric.api.Ecosystem.FABRIC),n->vanilla));
  ClassNode off=drawHook("Off","Lorg/spongepowered/asm/mixin/injection/Inject;","AFTER",net.neoforbric.api.Ecosystem.FABRIC);System.setProperty(FabricClientMixinAnchors.PROPERTY,"off");
  assertEquals(0,FabricClientMixinAnchors.adapt(off,n->merged));
  MixinStubRebind.forget();
 }
}