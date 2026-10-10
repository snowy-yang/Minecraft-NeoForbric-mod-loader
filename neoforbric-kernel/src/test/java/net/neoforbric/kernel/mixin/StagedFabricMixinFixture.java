package net.neoforbric.kernel.mixin;
import java.io.*;
import java.nio.file.*;
import java.util.List;
import java.util.zip.*;
import net.neoforbric.kernel.TestFixtures;
import net.neoforbric.kernel.TestFixtures.Fixture;
import net.neoforbric.kernel.mapping.ForgeModRemapper;
import net.neoforbric.kernel.mapping.NeoForbricMappings;
import net.neoforbric.kernel.mapping.RefmapRemapper;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;
/** Reads unmodified upstream modules; no test-written stand-in for their injection contracts. */
final class StagedFabricMixinFixture {
 static ClassNode mixin(String module,String name)throws Exception{
  Path api=TestFixtures.fabricApi();TestFixtures.require(Fixture.STAGED,Files.isRegularFile(api),"actual Fabric API fixture required");
  try(ZipFile z=new ZipFile(api.toFile())){
   ZipEntry e=z.stream().filter(x->x.getName().startsWith("META-INF/jars/"+module+"-")).findFirst().orElseThrow();
   try(ZipInputStream inner=new ZipInputStream(z.getInputStream(e))){for(ZipEntry entry;(entry=inner.getNextEntry())!=null;)if(entry.getName().equals(name+".class"))return MixinFit.parse(inner.readAllBytes());}
  }
  throw new AssertionError("actual mixin not found: "+name);
 }
 static ClassNode living(boolean vanilla)throws Exception{
  return game("net/minecraft/world/entity/LivingEntity",vanilla);
 }
 static ClassNode game(String name,boolean vanilla)throws Exception{
  Path p=vanilla?TestFixtures.vanillaJar():TestFixtures.stagedRoot().resolve("neoforge-base/patched-mc-neoforge-1.21.1.jar");
  TestFixtures.require(vanilla?Fixture.MC_LIBRARIES:Fixture.STAGED,Files.isRegularFile(p),"actual game required");
  try(ZipFile z=new ZipFile(p.toFile())){return MixinFit.parse(z.getInputStream(z.getEntry(name+".class")).readAllBytes());}
 }
 static MethodNode method(ClassNode c,String name){return c.methods.stream().filter(m->m.name.equals(name)).findFirst().orElseThrow();}
 static AnnotationNode at(ClassNode c,String name){return MixinFit.atNodes(MixinFit.injectorOf(method(c,name))).getFirst();}
 static byte[] bytes(ClassNode c){ClassWriter w=new ClassWriter(0);c.accept(w);return w.toByteArray();}
 private static NeoForbricMappings MAPPINGS;
 private static Path INTERMEDIARY_GAME;
 private static final java.util.Map<String,ClassNode> VANILLA_CLASSES=new java.util.HashMap<>();
 /** The module as the kernel loads it: fabric-api 0.116.17+1.21.1 ships intermediary-typed modules, and the
  * runtime form — the one the anchor adapters judge — is that module remapped into the base's Mojmap names by
  * the same production passes the build stages its transfer modules with (FabricModuleRemapTool's intermediary
  * game, then the module remap that also rewrites the refmap values). The module's own annotation strings keep
  * the names it was authored with (Yarn); Mixin resolves those through its refmap, while every class reference
  * and descriptor in its code is already the base's Mojmap. */
 static ClassNode remapped(String module,String name)throws Exception{
  Path api=TestFixtures.fabricApi();TestFixtures.require(Fixture.STAGED,Files.isRegularFile(api),"actual Fabric API fixture required");
  Path jar=remappedModule(api,module);
  try(ZipFile z=new ZipFile(jar.toFile())){
   ZipEntry e=z.getEntry(name+".class");
   if(e==null)throw new AssertionError("actual mixin not found in the remapped module: "+name);
   return MixinFit.parse(z.getInputStream(e).readAllBytes());
  }
 }
 /** The same class as the patched base's, from the unpatched vanilla 1.21.1 the patches were applied to,
  * in the base's Mojmap names: one class out of the intermediary cache of that vanilla client. */
 static ClassNode vanilla(String name)throws Exception{
  ClassNode cached=VANILLA_CLASSES.get(name);if(cached!=null)return cached;
  NeoForbricMappings mappings=mappings();Path game=intermediaryGame(mappings);
  String entry=mappings.mapClass(name)+".class";
  Path work=Path.of("build/test-vanilla-classes",name.replace('/','_'));
  Files.createDirectories(work);
  Path input=work.resolve("input.jar"),out=work.resolve("named.jar");
  Files.deleteIfExists(out);
  try(ZipOutputStream zo=new ZipOutputStream(Files.newOutputStream(input))){
   zo.putNextEntry(new ZipEntry(entry));zo.write(TestFixtures.requireEntry(Fixture.STAGED,game,entry));
  }
  ForgeModRemapper.remapJar(input,out,ForgeModRemapper.provider(mappings,NeoForbricMappings.INTERMEDIARY,NeoForbricMappings.NAMED),List.of(game),null);
  ClassNode node;
  try(ZipFile z=new ZipFile(out.toFile())){
   node=MixinFit.parse(z.getInputStream(z.stream().filter(x->x.getName().endsWith(".class")).findFirst().orElseThrow()).readAllBytes());
  }
  VANILLA_CLASSES.put(name,node);return node;
 }
 private static Path remappedModule(Path api,String module)throws Exception{
  Path raw=Path.of("build/test-module-remap",module+"-raw.jar"),out=Path.of("build/test-module-remap",module+"-remapped.jar");
  String digest;
  try(ZipFile z=new ZipFile(api.toFile())){
   ZipEntry e=z.stream().filter(x->x.getName().startsWith("META-INF/jars/"+module+"-")).findFirst().orElseThrow();
   byte[] bytes;try(InputStream in=z.getInputStream(e)){bytes=in.readAllBytes();}
   digest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
   if(Files.isRegularFile(out)&&Files.isRegularFile(pathOf(out+".raw-sha256"))&&digest.equals(Files.readString(pathOf(out+".raw-sha256")).trim()))return out;
   Files.createDirectories(raw.getParent());Files.write(raw,bytes);
  }
  NeoForbricMappings mappings=mappings();Path classpath=intermediaryGame(mappings);
  Path tmp=Files.createTempFile(out.getParent(),"remap",".jar");
  ForgeModRemapper.remapJar(raw,tmp,ForgeModRemapper.provider(mappings,NeoForbricMappings.INTERMEDIARY,NeoForbricMappings.NAMED),List.of(classpath,raw),RefmapRemapper.Names.from(mappings));
  Files.move(tmp,out,StandardCopyOption.REPLACE_EXISTING);
  Files.writeString(pathOf(out+".raw-sha256"),digest);
  return out;
 }
 /** A published Fabric mod's class as the kernel loads it: the same production remap the staged modules get
  * (FabricModuleRemapTool's intermediary→Mojmap over the classes, RefmapRemapper over the refmap values), run
  * over the mod's own jar. Real 1.21.1 Fabric mods ship intermediary-typed and the runtime hands the adapters
  * the Mojmap form, so a test reading the raw jar would judge a class the kernel never runs. */
 static ClassNode remapped(Path jar,String name)throws Exception{
  Path out=Path.of("build/test-mod-remap",jar.getFileName()+"-remapped.jar");
  byte[] bytes=Files.readAllBytes(jar);
  String digest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
  if(!(Files.isRegularFile(out)&&Files.isRegularFile(pathOf(out+".raw-sha256"))&&digest.equals(Files.readString(pathOf(out+".raw-sha256")).trim()))){
   Files.createDirectories(out.getParent());
   NeoForbricMappings mappings=mappings();Path classpath=intermediaryGame(mappings);
   Path tmp=Files.createTempFile(out.getParent(),"remap",".jar");
   ForgeModRemapper.remapJar(jar,tmp,ForgeModRemapper.provider(mappings,NeoForbricMappings.INTERMEDIARY,NeoForbricMappings.NAMED),List.of(classpath,jar),RefmapRemapper.Names.from(mappings));
   Files.move(tmp,out,StandardCopyOption.REPLACE_EXISTING);
   Files.writeString(pathOf(out+".raw-sha256"),digest);
  }
  try(ZipFile z=new ZipFile(out.toFile())){
   ZipEntry e=z.getEntry(name+".class");
   if(e==null)throw new AssertionError("actual mixin not found in the remapped mod jar: "+name);
   return MixinFit.parse(z.getInputStream(e).readAllBytes());
  }
 }
 private static Path pathOf(String path){return Path.of(path);}
 private static NeoForbricMappings mappings()throws Exception{
  if(MAPPINGS==null){
   Path tiny=devInput("mappings/intermediary-1.21.1.tiny"),mojmap=devInput("minecraft/.neoforbric-build/nfrt/artifacts/minecraft_1.21.1_client_mappings.txt");
   TestFixtures.requireFiles(Fixture.STAGED,"the mapping inputs the module remap reads (python3 ../tools/dev.py prepare stages them)",tiny,mojmap);
   MAPPINGS=NeoForbricMappings.load(tiny,mojmap);
  }
  return MAPPINGS;
 }
 /** A staged dev input ({@code .dev/...}), probing upward from the staged tree so a second worktree reads its
  * own stage's inputs, never this checkout's. */
 private static Path devInput(String relative){
  for(Path root=TestFixtures.stagedRoot();root!=null;root=root.getParent()){
   if(Files.isRegularFile(root.resolve(relative)))return root.resolve(relative);
   if(Files.isRegularFile(root.resolve(".dev").resolve(relative)))return root.resolve(".dev").resolve(relative);
  }
  return Path.of(".dev").resolve(relative);
 }
 private static Path intermediaryGame(NeoForbricMappings mappings)throws Exception{
  if(INTERMEDIARY_GAME!=null)return INTERMEDIARY_GAME;
  Path cache=Path.of("build/transfer-api-cache/minecraft-intermediary.jar");
  if(!Files.isRegularFile(cache)){
   Path client=devInput("minecraft/versions/1.21.1/1.21.1.jar");
   TestFixtures.require(Fixture.STAGED,Files.isRegularFile(client),"the vanilla client jar the remap reads");
   Files.createDirectories(cache.getParent());
   Path tmp=Files.createTempFile(cache.getParent(),"minecraft-intermediary",".jar");
   ForgeModRemapper.remapJar(client,tmp,ForgeModRemapper.provider(mappings,NeoForbricMappings.OFFICIAL,NeoForbricMappings.INTERMEDIARY),List.of());
   Files.move(tmp,cache,StandardCopyOption.REPLACE_EXISTING);
  }
  INTERMEDIARY_GAME=cache;return cache;
 }
}
