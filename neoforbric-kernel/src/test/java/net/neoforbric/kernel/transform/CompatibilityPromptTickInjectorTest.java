package net.neoforbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import net.neoforbric.kernel.TestFixtures;
import net.neoforbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

class CompatibilityPromptTickInjectorTest {
	private static final String OWNER = "net/neoforbric/kernel/runtime/KernelCompatibilityPrompts";

	@Test void installsOneClientTickCallbackAndIsIdempotent() {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "net/minecraft/client/Minecraft", null, "java/lang/Object", null);
		var tick = writer.visitMethod(Opcodes.ACC_PUBLIC, "tick", "()V", null, null);
		tick.visitCode(); tick.visitInsn(Opcodes.RETURN); tick.visitMaxs(0, 1); tick.visitEnd(); writer.visitEnd();
		CompatibilityPromptTickInjector injector = new CompatibilityPromptTickInjector();
		byte[] out = injector.transform("net.minecraft.client.Minecraft", writer.toByteArray(), null);
		assertEquals(1, calls(parse(out), OWNER, "tick"));
		assertSame(out, injector.transform("net.minecraft.client.Minecraft", out, null));
		assertSame(out, injector.transform("net.minecraft.server.MinecraftServer", out, null));
	}

	@Test void bootChecksAfterItsEvidenceAndResetsBeforeReadingMods() throws Exception {
		ClassNode boot = parse(Files.readAllBytes(Path.of("build/classes/java/main/net/neoforbric/kernel/boot/KernelBoot.class")));
		MethodNode launch = boot.methods.stream().filter(m -> m.name.equals("launch")).findFirst().orElseThrow();
		int reset = -1, report = -1, decision = -1, step = 0;
		for (AbstractInsnNode instruction : launch.instructions) {
			if (instruction instanceof MethodInsnNode call) {
				if (call.owner.equals("net/neoforbric/api/CompatibilityFindings") && call.name.equals("reset")) reset = step;
				// The pre-launch boundary writes the evidence (settle, observe, machine report, queue) without claiming
				// that loading finished; KernelLoadReportTest pins that it never calls the end-of-loading write().
				if (call.owner.equals("net/neoforbric/kernel/boot/KernelLoadReport") && call.name.equals("writeEvidence")) report = step;
				if (call.owner.equals("net/neoforbric/kernel/ui/CompatibilityDecision") && call.name.equals("requireContinuation")) decision = step;
			}
			step++;
		}
		assertTrue(reset >= 0 && report > reset && decision > report, "collect and settle before asking the player");
		assertEquals(1, calls(boot, "net/neoforbric/kernel/mixin/MixinCompatibility", "reset"));
		assertTrue(net.neoforbric.kernel.boot.KernelRuntimeClasses.all().containsKey(OWNER.replace('/', '.')));
	}

	/**
	 * Re-anchored: this was {@code gameUiCallsExistOnTheActualMinecraft262Base} and read the 26.x member set out of
	 * the base — {@code Minecraft.disconnectWithSavingScreen()}, and {@code Gui.setScreen(Screen)}. Neither exists on
	 * 1.21.1: the saving-screen disconnect is gone (1.21.1's vanilla path saves server-side before the client drops,
	 * so {@code KernelCompatibilityPrompts} calls plain {@code disconnect()}), and {@code setScreen} is on
	 * {@code Minecraft}, not {@code Gui}. What the test is for is unchanged — that every member the prompt drives
	 * exists on the base it is loaded into — so it asserts that set, each one by descriptor, and the 26.x name the
	 * runtime's comment says is gone.
	 */
	@Test void gameUiCallsExistOnTheActual1211Base() throws Exception {
		Path game = TestFixtures.stagedRoot().resolve("neoforge-base/patched-mc-neoforge-1.21.1.jar");
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(game), "staged game absent");
		try (ZipFile zip = new ZipFile(game.toFile())) {
			ClassNode minecraft = parse(zip.getInputStream(zip.getEntry("net/minecraft/client/Minecraft.class")).readAllBytes());
			for (String member : java.util.List.of("tick()V", "stop()V", "disconnect()V", "isRunning()Z",
					"getOverlay()Lnet/minecraft/client/gui/screens/Overlay;",
					"setScreen(Lnet/minecraft/client/gui/screens/Screen;)V")) {
				assertTrue(minecraft.methods.stream().anyMatch(m -> member.equals(m.name + m.desc)), member);
			}
			assertTrue(minecraft.fields.stream().anyMatch(f -> "screen".equals(f.name)
					&& "Lnet/minecraft/client/gui/screens/Screen;".equals(f.desc)), "Minecraft.screen");
			assertFalse(minecraft.methods.stream().anyMatch(m -> m.name.equals("disconnectWithSavingScreen")),
					"26.x's saving-screen disconnect is not on this base — the runtime's comment on that rename is "
							+ "load-bearing, and if the method comes back the prompt must be re-derived");
			parse(zip.getInputStream(zip.getEntry("net/minecraft/client/gui/screens/Overlay.class")).readAllBytes());
			ClassNode confirm = parse(zip.getInputStream(zip.getEntry("net/minecraft/client/gui/screens/ConfirmScreen.class")).readAllBytes());
			assertTrue(confirm.methods.stream().anyMatch(m -> m.name.equals("<init>") && m.desc.equals(
					"(Lit/unimi/dsi/fastutil/booleans/BooleanConsumer;Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/Component;)V")));
		}
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0); return node;
	}
	private static int calls(ClassNode node, String owner, String name) {
		int count = 0;
		for (MethodNode method : node.methods) for (AbstractInsnNode instruction : method.instructions) {
			if (instruction instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) count++;
		}
		return count;
	}
}
