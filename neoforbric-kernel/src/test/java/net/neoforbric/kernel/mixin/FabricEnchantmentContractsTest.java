package net.neoforbric.kernel.mixin;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.neoforbric.kernel.transform.FabricItemContractTransformer;
import net.neoforbric.kernel.transform.DuplicateLambdaPruneInjector;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
@ResourceLock("system-properties")
class FabricEnchantmentContractsTest {
 private final FabricItemContractTransformer transformer=new FabricItemContractTransformer(n->true);
 @AfterEach void reset(){System.clearProperty(FabricItemContractTransformer.PROPERTY);}
 // The module ships intermediary-typed classes and Yarn-authored annotation strings; the kernel hands the
 // transformers the Mojmap form, so a test reading the raw jar would judge a class the kernel never runs.
 private ClassNode api()throws Exception{return StagedFabricMixinFixture.resolved("fabric-item-api-v1",FabricItemContractTransformer.API);}
 private ClassNode transformed(String name,ClassNode node){return MixinFit.parse(transformer.transform(name.replace('/','.'),StagedFabricMixinFixture.bytes(node),null));}
 @Test void theActualDefaultFallsBackToBothNativeItemDecisions()throws Exception{
  ClassNode changed=transformed(FabricItemContractTransformer.API,api());MethodNode method=StagedFabricMixinFixture.method(changed,"canBeEnchantedWith");
  assertEquals(1,count(method,FabricItemContractTransformer.NATIVE,"supportsEnchantment"));assertEquals(1,count(method,FabricItemContractTransformer.NATIVE,"isPrimaryItemFor"));
  assertEquals(0,count(method,"net/minecraft/world/item/enchantment/Enchantment","canEnchant"));
  new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.BasicVerifier()).analyze(changed.name,method);
 }
 @Test void absentNativeApiDisabledAdapterAndUnknownDefaultBodyAreNotRewritten()throws Exception{
  byte[] bytes=StagedFabricMixinFixture.bytes(api());String name=FabricItemContractTransformer.API.replace('/','.');
  assertSame(bytes,new FabricItemContractTransformer(n->n.equals(FabricItemContractTransformer.API)).transform(name,bytes,null));
  System.setProperty(FabricItemContractTransformer.PROPERTY,"off");assertSame(bytes,transformer.transform(name,bytes,null));System.clearProperty(FabricItemContractTransformer.PROPERTY);
  ClassNode node=api();StagedFabricMixinFixture.method(node,"canBeEnchantedWith").instructions.insert(new InsnNode(Opcodes.NOP));bytes=StagedFabricMixinFixture.bytes(node);assertSame(bytes,transformer.transform(name,bytes,null));
 }
 @Test void theRealMethodReferenceGetsOneTypedWrapperWithoutChangingCaptureOrder()throws Exception{
  ClassNode node=transformed(FabricItemContractTransformer.HELPER,StagedFabricMixinFixture.game(FabricItemContractTransformer.HELPER,false));
  MethodNode bridge=StagedFabricMixinFixture.method(node,FabricItemContractTransformer.PRIMARY_HELPER);assertEquals(FabricItemContractTransformer.PRIMARY_DESC,bridge.desc);assertEquals(1,count(bridge,FabricItemContractTransformer.NATIVE,"isPrimaryItemFor"));
  int handles=0;for(var instruction:StagedFabricMixinFixture.method(node,"getAvailableEnchantmentResults").instructions)if(instruction instanceof InvokeDynamicInsnNode d)for(Object argument:d.bsmArgs)if(argument instanceof Handle h&&h.getName().equals(FabricItemContractTransformer.PRIMARY_HELPER)){handles++;assertEquals(Opcodes.H_INVOKESTATIC,h.getTag());assertEquals(FabricItemContractTransformer.PRIMARY_DESC,h.getDesc());}
  assertEquals(1,handles);byte[] bytes=StagedFabricMixinFixture.bytes(node);assertSame(bytes,transformer.transform(node.name.replace('/','.'),bytes,null));
 }
 /**
  * What this used to assert: all four of the module's mixins attach to the corresponding native decision — the
  * three commands/loot/helper ones and the anvil. On this anchor the anvil's row cannot fire: 1.21.1's
  * {@code AnvilMenu} has no {@code createResultInternal} and the row is keyed on a class the module does not ship
  * (its {@code createResult} carries the body and its own {@code supportsEnchantment} call), so
  * {@link #theAnvilsRowDeclinesBecauseThisBaseHasNoRenamedBody()} covers it separately and the loop below keeps the
  * three that do.
  */
 @Test void allFourActualFabricMixinsAttachToTheCorrespondingNativeDecision()throws Exception{
  for(String[] pair:List.of(new String[]{"EnchantCommandMixin","net/minecraft/server/commands/EnchantCommand"},new String[]{"EnchantRandomlyLootFunctionMixin","net/minecraft/world/level/storage/loot/functions/EnchantRandomlyFunction"},new String[]{"EnchantmentHelperMixin",FabricItemContractTransformer.HELPER})){
   ClassNode mixin=StagedFabricMixinFixture.resolved("fabric-item-api-v1","net/fabricmc/fabric/mixin/item/"+pair[0]);ClassNode target=StagedFabricMixinFixture.game(pair[1],false);
   target=MixinFit.parse(new DuplicateLambdaPruneInjector().transform(pair[1].replace('/','.'),StagedFabricMixinFixture.bytes(target),null));
   if(pair[1].equals(FabricItemContractTransformer.HELPER))target=transformed(pair[1],target);ClassNode finalTarget=target;
   assertEquals(1,FabricEnchantmentMixinAdapter.adapt(mixin,n->finalTarget),pair[0]);
   MethodNode handler=mixin.methods.stream().filter(m->MixinFit.injectorOf(m)!=null&&m.name.contains("Enchanting")).findFirst().orElseThrow();
   assertEquals(2,Type.getArgumentTypes(handler.desc).length);assertEquals(1,count(handler,FabricItemContractTransformer.STACK,"canBeEnchantedWith"));
   new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.BasicVerifier()).analyze(mixin.name,handler);
   assertEquals(0,FabricEnchantmentMixinAdapter.adapt(mixin,n->finalTarget));
  }
 }
 /**
  * The anvil's row is 26.x twice over, and neither half can be settled by a constant: it is keyed on the class name
  * {@code AnvilMenuMixin}, which 0.116.17 does not ship (it ships {@code AnvilScreenHandlerMixin}), and it re-points
  * the handler into {@code AnvilMenu.createResultInternal}, which 1.21.1 does not have — its {@code createResult}
  * carries the body and its own {@code supportsEnchantment} call. Re-anchoring it means deciding what the anvil's
  * contract is here, which is a design change, so the row is left as it is and reported as a gap: the anvil's
  * redirect is never re-pointed on this anchor.
  */
 @Test void theAnvilsRowDeclinesBecauseThisBaseHasNoRenamedBody()throws Exception{
  assertThrows(AssertionError.class,()->StagedFabricMixinFixture.mixin("fabric-item-api-v1","net/fabricmc/fabric/mixin/item/AnvilMenuMixin"),"the row's mixin name is not the one the module ships");
  ClassNode mixin=StagedFabricMixinFixture.resolved("fabric-item-api-v1","net/fabricmc/fabric/mixin/item/AnvilScreenHandlerMixin"),target=StagedFabricMixinFixture.game("net/minecraft/world/inventory/AnvilMenu",false);
  assertEquals(1,target.methods.stream().filter(m->m.name.equals("createResult")).count());
  assertTrue(target.methods.stream().noneMatch(m->m.name.equals("createResultInternal")),"the 26.x host is absent");
  assertTrue(target.methods.stream().filter(m->m.name.equals("createResult")).anyMatch(m->java.util.Arrays.stream(m.instructions.toArray()).anyMatch(i->i instanceof MethodInsnNode c&&c.name.equals("supportsEnchantment"))),"the body and its decision are in createResult itself");
  assertEquals(0,FabricEnchantmentMixinAdapter.adapt(mixin,n->target));
 }
 @Test void modifiedHandlerAndAlternativeGroupKeepTheirOriginalContract()throws Exception{
  for(boolean group:new boolean[]{false,true}){
   ClassNode mixin=StagedFabricMixinFixture.resolved("fabric-item-api-v1","net/fabricmc/fabric/mixin/item/EnchantCommandMixin"),target=StagedFabricMixinFixture.game("net/minecraft/server/commands/EnchantCommand",false);
   MethodNode method=StagedFabricMixinFixture.method(mixin,"callAllowEnchantingEvent");
   if(group)method.visibleAnnotations.add(new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Group;"));else method.instructions.insert(new InsnNode(Opcodes.NOP));
   assertEquals(0,FabricEnchantmentMixinAdapter.adapt(mixin,n->target));
  }
 }
 private int count(MethodNode method,String owner,String name){int n=0;for(var i:method.instructions)if(i instanceof MethodInsnNode c&&c.owner.equals(owner)&&c.name.equals(name))n++;return n;}
}
