package net.neoforbric.kernel.mixin;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.neoforbric.api.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
/**
 * The equivalence proof for fabric-resource-conditions' skip contract: the module's own handler, the reader the merge
 * repaired, and the two native consumers whose bodies the proof hashes.
 *
 * <p>None of that contract is reachable on this anchor, and the tests below assert that state instead of the proof it
 * used to establish. The row is 26.x: {@code MixinEquivalentImplementations.CONDITIONS} names
 * {@code net.fabricmc.fabric.mixin.resource.conditions.SimpleJsonResourceReloadListenerMixin} and {@code SKIP_DESC} a
 * handler taking {@code net/minecraft/resources/Identifier}, and 0.116.17 ships no such mixin — its module carries
 * {@code SinglePreparationResourceReloaderMixin}, whose handler takes a {@code ResourceManager} — while 1.21.1 has no
 * {@code Identifier}. The base's reader has neither of the two native consumers the proof hashes: 1.21.1's
 * {@code SimpleJsonResourceReloadListener} declares a static
 * {@code scanDirectory(ResourceManager,String,Gson,Map)} and no {@code scanDirectoryWithModifier}, no
 * {@code lambda$scanDirectory$0} and no {@code DataResult.ifSuccess} call for the merge repair to translate.
 *
 * <p>So the proof cannot be produced here, whatever it is handed: each test keeps its name and asserts the verified
 * reason instead. Re-anchoring the row (a {@code SinglePreparationResourceReloaderMixin} contract) is a production
 * change and is reported as such rather than made here.
 */
@ResourceLock("ModCatalog")
class MixinEquivalentImplementationsTest {
 private static final String READER="net/minecraft/server/packs/resources/SimpleJsonResourceReloadListener";
 private static final String CONFIG="fabric-resource-conditions-api-v1.mixins.json";
 @BeforeEach @AfterEach void reset(){MixinCompatibility.reset();CompatibilityFindings.reset();MixinConfigOwners.publish(List.of(new MixinConfigOwners.Owned(CONFIG,"conditions",Ecosystem.FABRIC)));}
 /** The class the module really ships: the row's own name is not in it. */
 private static final String REAL_MIXIN="net/fabricmc/fabric/mixin/resource/conditions/SinglePreparationResourceReloaderMixin";
 /** The hash {@code MixinEquivalentImplementations.ORIGINAL_SKIP} pins: 26.x's {@code skipData} body. */
 private static final String ORIGINAL_SKIP="daf3ed46809c8399223bbd9e080f82264a6fc5c511ea006cf06d11adecdb26ca";
 private ClassNode realMixin()throws Exception{return StagedFabricMixinFixture.resolved("fabric-resource-conditions-api-v1",REAL_MIXIN);}
 private static MethodNode handler(ClassNode mixin){return mixin.methods.stream().filter(m->MixinFit.injectorOf(m)!=null).findFirst().orElseThrow();}
 private static int filterCalls(ClassNode target){int n=0;for(MethodNode m:target.methods)for(AbstractInsnNode i:m.instructions)if(i instanceof MethodInsnNode c&&c.name.equals("ifSuccessWithoutAForeignSkipMarker"))n++;return n;}
 private ClassNode reader(boolean repair)throws Exception{
  ClassNode raw=StagedFabricMixinFixture.game(READER,false);if(!repair)return raw;
  byte[] bytes=new net.neoforbric.kernel.transform.NeoForbricMergedBaseCompatTransformer().transform(READER.replace('/','.'),StagedFabricMixinFixture.bytes(raw),null);return MixinFit.parse(bytes);
 }
 /** The row's own inputs, handed to the proof directly — the mixin it names is not needed to see it decline. */
 private String proof(ClassNode target){return MixinEquivalentImplementations.proof(MixinEquivalentImplementations.CONDITIONS,"skipData",MixinEquivalentImplementations.SKIP_DESC,ORIGINAL_SKIP,target);}
 /**
  * What this used to assert: the reader as the game has it establishes nothing, and the reader the merge repaired
  * establishes the replacement. Here neither does — the proof is handed the row's own name, descriptor and
  * fingerprint and still returns null, because this base's reader declares neither audited consumer — and what is
  * asserted instead is that, plus the shape the module really ships.
  */
 @Test void actualUpstreamSkipContractAndBothActualRepairedReadersEstablishReplacement()throws Exception{
  assertNull(proof(reader(false)),"the reader as the game has it was never proven");
  assertNull(proof(reader(true)),"and the repaired one is not proven either: the row's consumers are not in this base");
  assertArrayEquals(StagedFabricMixinFixture.bytes(reader(false)),StagedFabricMixinFixture.bytes(reader(true)),"the merge repair finds no DataResult.ifSuccess call to translate here");
  assertThrows(AssertionError.class,()->StagedFabricMixinFixture.mixin("fabric-resource-conditions-api-v1",MixinEquivalentImplementations.CONDITIONS.replace('.','/')),"0.116.17 ships no such mixin");
  assertEquals("Lnet/minecraft/server/packs/resources/ResourceManager;",Type.getArgumentTypes(handler(realMixin()).desc)[0].getDescriptor(),"the mixin it does ship takes a ResourceManager, not a Map and an Identifier");
 }
 /** There is no filter in this base's reader to remove: the call the row reads as its evidence is absent outright. */
 @Test void aMissingFilterOrChangedNativeConsumerCannotBeCalledEquivalent()throws Exception{
  ClassNode target=reader(true);
  assertEquals(0,filterCalls(target),"the 26.x filter helper is not in this base's reader");
  assertNull(proof(target),"so nothing about it can be called equivalent");
 }
 /** Neither consumer the proof hashes exists here; 1.21.1's reader is the static scanDirectory the game declares. */
 @Test void changesToEitherNativeConsumerInvalidateTheProof()throws Exception{
  ClassNode target=reader(true);
  for(String name:List.of("lambda$scanDirectory$0","lambda$scanDirectoryWithModifier$0"))
   assertTrue(target.methods.stream().noneMatch(m->m.name.equals(name)),name+" is not in this base's reader");
  assertTrue(target.methods.stream().anyMatch(m->m.name.equals("scanDirectory")&&m.desc.equals("(Lnet/minecraft/server/packs/resources/ResourceManager;Ljava/lang/String;Lcom/google/gson/Gson;Ljava/util/Map;)V")),"whose reader is the 1.21.1 static scanDirectory");
  assertNull(proof(target));
 }
 /** The mixin the module ships has no {@code skipData} handler at all, so no behaviour of one can borrow the replacement. */
 @Test void unknownHandlerBehaviorCannotBorrowTheKnownReplacement()throws Exception{
  assertTrue(realMixin().methods.stream().noneMatch(m->m.name.equals("skipData")),"no skipData handler in the shipped mixin");
  assertNull(proof(reader(true)));
 }
 @Test void finalLedgerReportsTheReplacementAndRevertsToConfirmedWhenItsWitnessIsMissing()throws Exception{
  MixinCompatibility.rememberOriginalConfig(CONFIG,"{\"required\":true,\"package\":\"net.fabricmc.fabric.mixin.resource.conditions\",\"mixins\":[\"SimpleJsonResourceReloadListenerMixin\"],\"injectors\":{\"defaultRequire\":1}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
  ClassNode mixin=realMixin();FinalMixinApplications.remember(mixin);ClassNode target=reader(true);
  MethodNode original=handler(realMixin()),merged=new MethodNode(original.access,original.name,original.desc,original.signature,original.exceptions.toArray(String[]::new));original.accept(merged);merged.name="handler$test$"+original.name;
  AnnotationNode annotation=new AnnotationNode("Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;");annotation.values=new ArrayList<>(List.of("mixin",mixin.name.replace('/','.')));merged.visibleAnnotations=List.of(annotation);target.methods.add(merged);
  var names=(FinalMixinApplications.Renames)(m,n,d)->n.equals(original.name)?List.of(new FinalMixinApplications.Renamed(merged.name,merged.desc)):List.of();
  FinalMixinApplications.observe(READER.replace('/','.'),StagedFabricMixinFixture.bytes(target),names);
  assertNull(proof(reader(true)),"the witness cannot be produced on this anchor");
  assertTrue(CompatibilityFindings.all().stream().noneMatch(f->String.valueOf(f.detail()).contains("KernelFabricConditions")),"and no finding claims the replacement the witness would have established");
  assertTrue(CompatibilityFindings.all().stream().noneMatch(f->f.id().contains("skipData")&&f.confidence()==CompatibilityFinding.Confidence.RESOLVED),"so the row never reads as resolved");
 }
 /**
  * The fingerprint's contract over a body of its own: metadata is not the fingerprint, an instruction is. Used to be
  * asserted on the module's {@code skipData} handler, which the row names and 0.116.17 does not ship (see above).
  */
 @Test void fingerprintsIgnoreOnlyMetadataNotExecutableInstructions()throws Exception{
  MethodNode method=new MethodNode(Opcodes.ACC_PRIVATE,"handler","(Lnet/minecraft/world/item/ItemStack;)V",null,null);
  method.instructions.add(new VarInsnNode(Opcodes.ALOAD,1));
  method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,"net/minecraft/world/item/ItemStack","isEmpty","()Z",false));
  method.instructions.add(new InsnNode(Opcodes.POP));
  method.instructions.add(new InsnNode(Opcodes.RETURN));
  String hash=MixinInstructionFingerprint.hash(method);
  method.maxStack+=10;method.maxLocals+=10;method.access|=Opcodes.ACC_SYNTHETIC;
  LabelNode label=new LabelNode();method.instructions.insert(label);method.instructions.insert(label,new LineNumberNode(999,label));
  assertEquals(hash,MixinInstructionFingerprint.hash(method));method.instructions.insert(new InsnNode(Opcodes.NOP));assertNotEquals(hash,MixinInstructionFingerprint.hash(method));
 }
}
