package net.neoforbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

import net.neoforbric.kernel.TestFixtures;
import net.neoforbric.kernel.TestFixtures.Fixture;

@ResourceLock("system-properties")
class UntrackedFluidEyeQueryInjectorTest {
    private static final Path MERGED = TestFixtures.stagedRoot().resolve("neoforge-base/patched-mc-neoforge-1.21.1.jar");
    @AfterEach void reset() { System.clearProperty(UntrackedFluidEyeQueryInjector.PROPERTY); }
    /**
     * Re-anchored: this drove the injector over both of its targets and asserted the rewritten bodies — a
     * {@code containsKey} on the interaction's tracker map, a native {@code getFluidTypeByTag} path kept behind the
     * guard. {@code net.minecraft.world.entity.EntityFluidInteraction} does not exist on 1.21.1 (this base tracks
     * fluids on the entity itself), and the injector's other target, {@code Entity.isEyeInFluid(TagKey)}, makes no
     * call at all for the guard to sit in front of, so the injector stands down on both — that is what is asserted,
     * plus the premise that the interaction class is absent, which is what would have to change for the guard to have
     * a target again. Nothing here claims an untracked fluid query is guarded on this base: it is not.
     */
    @Test void theInteractionIsGoneAndEntitysEyeQueryIsLeftNative() throws Exception {
        var repair = new UntrackedFluidEyeQueryInjector();
        TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED), "staged merged base absent: " + MERGED);
        try (ZipFile zip = new ZipFile(MERGED.toFile())) {
            assertNull(zip.getEntry(UntrackedFluidEyeQueryInjector.INTERACTION.replace('.', '/') + ".class"),
                    "the interaction class is back — re-derive the injector rather than this assertion");
            assertNotNull(zip.getEntry(UntrackedFluidEyeQueryInjector.ENTITY.replace('.', '/') + ".class"));
        }
        byte[] original = NativeCoremodParityTest.read(MERGED, UntrackedFluidEyeQueryInjector.ENTITY.replace('.', '/'));
        ClassNode node = new ClassNode(); new ClassReader(original).accept(node, 0);
        MethodNode method = node.methods.stream().filter(m -> m.name.equals("isEyeInFluid")
                && m.desc.equals(UntrackedFluidEyeQueryInjector.DESC)).findFirst().orElseThrow();
        assertTrue(calls(method, "isEyeInFluidType"),
                "1.21.1's query is NeoForge's own water/lava check, not a tracker lookup");
        assertFalse(calls(method, "getFluidTypeByTag"),
                "the interaction's native lookup the guard is written around is what the injector needs; it is absent");
        assertFalse(calls(method, "hasTagType"), "…and the guard is not spliced in");
        new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
        assertSame(original, repair.transform(UntrackedFluidEyeQueryInjector.ENTITY, original, null),
                "no interaction, no tracker map, nothing to guard");
        System.setProperty(UntrackedFluidEyeQueryInjector.PROPERTY, "off");
        assertSame(original, repair.transform(UntrackedFluidEyeQueryInjector.ENTITY, original, null));
        System.clearProperty(UntrackedFluidEyeQueryInjector.PROPERTY);
    }

    private static boolean calls(MethodNode method, String name) {
        return java.util.Arrays.stream(method.instructions.toArray())
                .anyMatch(i -> i instanceof MethodInsnNode call && call.name.equals(name));
    }
}
