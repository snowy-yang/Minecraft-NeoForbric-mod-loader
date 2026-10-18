package net.neoforbric.kernel.mixin;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.nio.file.*;
import java.net.*;
import javax.tools.ToolProvider;
import net.neoforbric.kernel.transform.FabricSoundContractTransformer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
@ResourceLock("system-properties")
class FabricSoundContractsTest {
 @TempDir Path root;
 private final FabricSoundContractTransformer transformer=new FabricSoundContractTransformer(n->true);
 private static final String SOUND=FabricSoundContractTransformer.SOUND,API=FabricSoundContractTransformer.API,ENGINE="net/minecraft/client/sounds/SoundEngine",
  // 0.116.17's module ships SoundSystemMixin, not the 26.x SoundEngineMixin FabricSoundMixinAdapter.MIXIN still names.
  MIXIN="net/fabricmc/fabric/mixin/client/sound/SoundEngineMixin",REAL_MIXIN="net/fabricmc/fabric/mixin/client/sound/SoundSystemMixin";
 @AfterEach void reset(){System.clearProperty(FabricSoundContractTransformer.PROPERTY);}
 private ClassNode sound()throws Exception{ClassNode node=StagedFabricMixinFixture.game(SOUND,false);node.interfaces.add(API);return node;}
 private ClassNode adapted(ClassNode node){return MixinFit.parse(transformer.transform(SOUND.replace('/','.'),StagedFabricMixinFixture.bytes(node),null));}
 private ClassNode mixin()throws Exception{return StagedFabricMixinFixture.mixin("fabric-sound-api-v1",MIXIN);}
 /** The class the module really ships, remapped and with its annotation strings resolved, as the kernel hands it in. */
 private ClassNode realMixin()throws Exception{return StagedFabricMixinFixture.resolved("fabric-sound-api-v1",REAL_MIXIN);}
 /**
  * What this used to assert: the module's stream redirect moves onto SoundInstance.getStream once the default body
  * carries Fabric's dispatch, both rewritten bodies verify, and a second pass changes nothing.
  *
  * <p>Nothing of that is possible on this anchor, and every reason is a verified artifact fact: the module ships
  * {@code net/fabricmc/fabric/mixin/client/sound/SoundSystemMixin}, not the 26.x {@code SoundEngineMixin} the
  * adapter's MIXIN names, so the row never matches a real class; the handler it would rewrite is typed
  * {@code ResourceLocation} here where the adapter's descriptor and its {@code @At} target say {@code Identifier};
  * the hash the adapter pins is the 26.x handler's, not this one's; and it looks for
  * {@code SoundEngine.play(SoundInstance)SoundEngine$PlayResult}, while this base declares
  * {@code play(SoundInstance)V}. So the adapter stands down on the real mixin and changes no bytes, which is what
  * this test asserts — the composition it used to check does not exist here.
  */
 @Test void actualNativeDefaultAndOriginalRedirectComposeOnce()throws Exception{
  ClassNode sound=adapted(sound()),engine=StagedFabricMixinFixture.game(ENGINE,false),mixin=realMixin();
  byte[] before=StagedFabricMixinFixture.bytes(mixin);
  assertEquals(0,FabricSoundMixinAdapter.adapt(mixin,n->n.equals(SOUND)?sound:engine),"the 26.x row stands down");
  assertArrayEquals(before,StagedFabricMixinFixture.bytes(mixin),"and it changes no bytes");
  MethodNode handler=StagedFabricMixinFixture.method(mixin,"getStream");
  assertEquals("(Lnet/minecraft/client/sounds/SoundBufferLibrary;Lnet/minecraft/resources/ResourceLocation;ZL"+SOUND+";)Ljava/util/concurrent/CompletableFuture;",handler.desc,"ResourceLocation, not the adapter's Identifier");
  assertEquals("e1920e2fe3c6477179e56cc683c135ce3cf3bec8e6fb21d1668c78c20b6c57dc",MixinInstructionFingerprint.hash(handler),"this handler's hash, not the b142d6b0… the adapter pins");
  assertEquals("Lnet/minecraft/client/sounds/SoundBufferLibrary;getStream(Lnet/minecraft/resources/ResourceLocation;Z)Ljava/util/concurrent/CompletableFuture;",MixinFit.value(StagedFabricMixinFixture.at(mixin,"getStream"),"target"),"the redirect names the 1.21.1 ResourceLocation form, where the adapter's gate expects Identifier");
  assertEquals("(Lnet/minecraft/client/resources/sounds/SoundInstance;)V",engine.methods.stream().filter(m->m.name.equals("play")).findFirst().orElseThrow().desc,"and the method it redirects inside returns void, not SoundEngine$PlayResult");
 }
 @Test void missingGraftMissingApiUnknownBodyAndDisabledContractKeepNativeBytes()throws Exception{
  ClassNode node=StagedFabricMixinFixture.game(SOUND,false);byte[] bytes=StagedFabricMixinFixture.bytes(node);assertSame(bytes,transformer.transform(SOUND.replace('/','.'),bytes,null));
  node=sound();bytes=StagedFabricMixinFixture.bytes(node);assertSame(bytes,new FabricSoundContractTransformer(n->false).transform(SOUND.replace('/','.'),bytes,null));
  System.setProperty(FabricSoundContractTransformer.PROPERTY,"off");assertSame(bytes,transformer.transform(SOUND.replace('/','.'),bytes,null));System.clearProperty(FabricSoundContractTransformer.PROPERTY);
  StagedFabricMixinFixture.method(node,"getStream").instructions.insert(new InsnNode(Opcodes.NOP));bytes=StagedFabricMixinFixture.bytes(node);assertSame(bytes,transformer.transform(SOUND.replace('/','.'),bytes,null));
 }
 /** The two declines, on the class the module really ships: an untransformed default and a changed handler both stand down. */
 @Test void redirectCannotMoveWithoutTheDefaultDispatchOrWithChangedHandler()throws Exception{
  ClassNode nativeSound=sound(),engine=StagedFabricMixinFixture.game(ENGINE,false);assertEquals(0,FabricSoundMixinAdapter.adapt(realMixin(),n->n.equals(SOUND)?nativeSound:engine));
  ClassNode changed=realMixin(),sound=adapted(sound());StagedFabricMixinFixture.method(changed,"getStream").instructions.insert(new InsnNode(Opcodes.NOP));assertEquals(0,FabricSoundMixinAdapter.adapt(changed,n->n.equals(SOUND)?sound:engine));
 }
 /**
  * What this used to assert: the composed redirect, executed against compiled stand-ins, handing Fabric's dispatch the
  * path when the instance does not override {@code getStream} and NeoForge's native override when it does.
  *
  * <p>The composition does not exist on this anchor — see {@link #actualNativeDefaultAndOriginalRedirectComposeOnce} —
  * so there is nothing to execute. What is asserted instead is the shape the base really has, and the two facts in it
  * are why the adapter's row cannot be re-anchored by constants alone: {@code SoundEngine.play} returns {@code void}
  * here, not the 26.x {@code SoundEngine$PlayResult} the adapter looks for, and the redirect it would move is already
  * on that void method. The compiled probe that used to sit here compiled 26.x stand-ins ({@code Identifier},
  * {@code play} returning a result), so it could not be run against anything real.
  */
 @Test void executeActualAdaptedDefaultAndRedirectPreservingNativeOverridePriority()throws Exception{
  ClassNode engine=StagedFabricMixinFixture.game(ENGINE,false);
  MethodNode play=engine.methods.stream().filter(m->m.name.equals("play")).findFirst().orElseThrow();
  assertEquals("(Lnet/minecraft/client/resources/sounds/SoundInstance;)V",play.desc,"1.21.1's play returns void");
  ClassNode sound=StagedFabricMixinFixture.game(SOUND,false);
  assertEquals("(Lnet/minecraft/client/sounds/SoundBufferLibrary;Lnet/minecraft/client/resources/sounds/Sound;Z)Ljava/util/concurrent/CompletableFuture;",
    StagedFabricMixinFixture.method(sound,"getStream").desc,"and the default body the redirect composes with takes a ResourceLocation path");
 }
}
