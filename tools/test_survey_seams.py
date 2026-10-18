"""The parsing halves of survey_seams.py, checked without the staged game jars.

The survey's numbers are cited in neoforbric-kernel/HOOKBUS.md, so the parts that can be
wrong without anyone noticing are the ones that translate names. A tiny file read
backwards, or a proguard file read in the wrong direction, still produces a plausible
count -- it just maps every seam to the wrong class. These tests pin the direction of
each read, and the `$`-suffix rule for inner classes.
"""
import tempfile
from pathlib import Path
import unittest

import survey_seams

TINY = '''v1\tofficial\tintermediary
CLASS\taqw\tnet/minecraft/class_3225
CLASS\tfgo\tnet/minecraft/class_310
CLASS\taaa\tnet/minecraft/class_1
'''

PROGUARD = '''# (c) 2020 Microsoft Corporation.
net.minecraft.server.level.ServerPlayerGameMode -> aqw:
    net.minecraft.world.item.Item item -> bcd:
net.minecraft.client.Minecraft -> fgo:
'''


class MappingTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='neoforbric seams ')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.tiny = self.root / 'mappings.tiny'
        self.tiny.write_text(TINY, encoding='utf-8')
        self.proguard = self.root / 'mojmap.proguard'
        self.proguard.write_text(PROGUARD, encoding='utf-8')

    def test_tiny_reads_obf_to_intermediary(self):
        self.assertEqual({'aqw': 'net/minecraft/class_3225', 'fgo': 'net/minecraft/class_310',
                          'aaa': 'net/minecraft/class_1'}, survey_seams.read_tiny(self.tiny))

    def test_proguard_reads_obf_to_mojmap_not_the_reverse(self):
        # The file is written `mojmap -> obf:`; reading it the other way would key on class names
        # and map every seam to an obfuscated name, which looks like a working run.
        self.assertEqual({'aqw': 'net.minecraft.server.level.ServerPlayerGameMode',
                          'fgo': 'net.minecraft.client.Minecraft'},
                         survey_seams.read_proguard(self.proguard))

    def test_the_chain_composes_both_directions(self):
        chain = survey_seams.build_intermediary_to_mojmap(self.tiny, self.proguard)
        # class_1 is in the tiny file but not the proguard file, so it cannot be resolved.
        self.assertEqual({'net/minecraft/class_3225': 'net.minecraft.server.level.ServerPlayerGameMode',
                          'net/minecraft/class_310': 'net.minecraft.client.Minecraft'}, chain)

    def test_a_member_line_in_the_proguard_file_is_not_a_class_mapping(self):
        # Member lines are indented; a regex that tolerated leading whitespace would read
        # `Item item -> bcd:` as the class mapping `bcd -> net.minecraft.world.item.Item`.
        read = survey_seams.read_proguard(self.proguard)
        self.assertNotIn('bcd', read)
        self.assertNotIn('net.minecraft.world.item.Item', read.values())


class ToMojmapTest(unittest.TestCase):
    CHAIN = {'net/minecraft/class_3225': 'net.minecraft.server.level.ServerPlayerGameMode',
             'net/minecraft/class_310': 'net.minecraft.client.Minecraft'}

    def test_an_intermediary_name_resolves(self):
        self.assertEqual('net.minecraft.client.Minecraft',
                         survey_seams.to_mojmap('net/minecraft/class_310', self.CHAIN))

    def test_an_inner_class_resolves_through_its_outer(self):
        # Mixin targets include inner classes; without the `$` rule these fall through unresolved
        # and the survey silently undercounts the classes both ecosystems want.
        self.assertEqual('net.minecraft.client.Minecraft$1',
                         survey_seams.to_mojmap('net/minecraft/class_310$1', self.CHAIN))

    def test_an_unknown_name_is_left_alone(self):
        self.assertEqual('com.example.Thing', survey_seams.to_mojmap('com/example/Thing', self.CHAIN))

    def test_an_already_mojmap_name_passes_through(self):
        self.assertEqual('net.minecraft.world.level.Level',
                         survey_seams.to_mojmap('net/minecraft/world/level/Level', self.CHAIN))


class HookClassListTest(unittest.TestCase):
    def test_every_entry_is_an_internal_name_of_a_hooks_class(self):
        # The survey greps the base jar for these strings; a dotted or misspelled entry finds
        # nothing and reports a clean, empty NeoForge side.
        for hook in survey_seams.HOOK_CLASSES:
            with self.subTest(hook=hook):
                self.assertNotIn('.', hook)
                self.assertTrue(hook.endswith('Hooks'), hook)
                self.assertTrue(hook.startswith('net/neoforged/neoforge/'), hook)

    def test_the_three_high_volume_hook_classes_are_present(self):
        for name in ('CommonHooks', 'EventHooks', 'ClientHooks'):
            with self.subTest(name=name):
                self.assertTrue(any(h.endswith('/' + name) for h in survey_seams.HOOK_CLASSES),
                                f'{name} missing from the survey')


if __name__ == '__main__':
    unittest.main()
