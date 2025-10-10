package build.tasks;

import static build.utils.AsmUtils.LATEST_ASM_API;
import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static java.util.function.Predicate.not;
import static org.objectweb.asm.ClassReader.SKIP_DEBUG;

import build.dto.GradleDependencies;
import build.dto.GradleDependencyInfo;
import com.google.common.collect.ImmutableSet;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayDeque;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.gradle.api.BuildCancelledException;
import org.gradle.api.internal.classpath.ModuleRegistry;
import org.gradle.api.tasks.CacheableTask;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.TypePath;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Scans Gradle module JARs to discover runtime dependencies declared via {@code ModuleRegistry} calls.
 *
 * <p>Reads {@link GradleDependencies} and inspects compiled class files in
 * Gradle JARs under {@link #getGradleFilesDirectory()}.
 * Uses the ASM library to analyze bytecode and detect invocations of {@code org.gradle.*.ModuleRegistry} methods
 * that reference other Gradle modules.
 *
 * <p>Processing logic:
 * <ul>
 *   <li>Iterates through all Gradle JARs whose names start with {@code gradle-}
 *   <li>For each class file, inspects method calls referencing {@link ModuleRegistry}
 *   <li>Detects argument constants representing module names and resolves corresponding module JARs
 *   <li>Links discovered modules as sub-dependencies within the existing dependency graph
 *   <li>Recursively processes new modules until all reachable modules are analyzed
 *   <li>Skips modules not explicitly whitelisted in {@link #ALLOWER_MODULES}
 * </ul>
 *
 * <p>Inputs:
 * <ul>
 *   <li>{@link #getGradleDependenciesFile()} – file with {@link GradleDependencies} from the previous stage
 *   <li>{@link #getGradleFilesDirectory()} – directory containing Gradle module JARs
 * </ul>
 *
 * <p>Outputs:
 * <ul>
 *   <li>{@link #getGradleDependenciesJsonFile()} – updated {@link GradleDependencies} file
 * </ul>
 */
@CacheableTask
public abstract class ProcessModuleRegistry extends AbstractMappingDependenciesInfoTask {

    private static final Set<String> ALLOWER_MODULES = ImmutableSet.of(
        "gradle-runtime-api-info"
    );

    @Override
    protected GradleDependencies mapGradleDependencies(GradleDependencies gradleDependencies) throws Exception {
        var queue = new ArrayDeque<>(gradleDependencies.getDependencies().entrySet());
        while (true) {
            var queueElement = queue.pollFirst();
            if (queueElement == null) {
                break;
            }

            var depId = queueElement.getKey();
            var depInfo = queueElement.getValue();

            var path = depInfo.getPath();
            if (path == null) {
                continue;
            }

            var file = getProjectRelativeFile(path);
            var isGradleFile = file.getName().startsWith("gradle-");
            if (!isGradleFile) {
                continue;
            }

            BiConsumer<String, String> moduleNameConsumer = (classInternalName, moduleName) -> {
                if (moduleName.equals(depId.getName())
                    || !ALLOWER_MODULES.contains(moduleName)
                ) {
                    return;
                }

                var moduleFile = getGradleModuleFile(moduleName);
                if (moduleFile == null) {
                    throw new IllegalStateException(format(
                        "%s: ModuleRegistry usage scan: module file not found for module: %s",
                        classInternalName,
                        moduleName
                    ));
                }

                var moduleDepId = gradleDependencies.getDependencyIdByPathOrName(moduleFile);
                depInfo.getDependencies().add(moduleDepId);

                if (!gradleDependencies.getDependencies().containsKey(moduleDepId)) {
                    var moduleDepInfo = new GradleDependencyInfo();
                    moduleDepInfo.setPath(getProjectFileRelativePath(moduleFile));

                    gradleDependencies.getDependencies().put(moduleDepId, moduleDepInfo);
                    queue.addLast(new SimpleImmutableEntry<>(moduleDepId, moduleDepInfo));
                }
            };

            try (var zipFile = new ZipFile(file, UTF_8)) {
                var classEntries = zipFile.stream()
                    .filter(not(ZipEntry::isDirectory))
                    .filter(entry -> entry.getName().endsWith(".class"))
                    .toList();
                for (var classEntry : classEntries) {
                    if (getBuildCancellationToken().isCancellationRequested()) {
                        throw new BuildCancelledException();
                    }

                    try (var in = zipFile.getInputStream(classEntry)) {
                        var classVisitor = new ModuleRegistryCallsClassVisitor(moduleNameConsumer);
                        var classReader = new ClassReader(in);
                        classReader.accept(classVisitor, SKIP_DEBUG);
                    }
                }
            }
        }

        return gradleDependencies;
    }

    private static class ModuleRegistryCallsMethodVisitor extends MethodNode {

        private final Consumer<String> moduleNameConsumer;

        public ModuleRegistryCallsMethodVisitor(Consumer<String> moduleNameConsumer) {
            super(LATEST_ASM_API);
            this.moduleNameConsumer = moduleNameConsumer;
        }

        @Override
        public void visitMethodInsn(
            int opcodeAndSource,
            String owner,
            String name,
            String descriptor,
            boolean isInterface
        ) {
            if (!(owner.startsWith("org/gradle/") && owner.endsWith("/ModuleRegistry"))) {
                return;
            }

            if (!(descriptor.startsWith("(Ljava/lang/String;)") && descriptor.endsWith("/Module;"))) {
                return;
            }

            var prevInstruction = instructions.getLast();
            if (prevInstruction instanceof LdcInsnNode ldc) {
                if (ldc.cst instanceof String string) {
                    moduleNameConsumer.accept(string);
                }
            }

            super.visitMethodInsn(opcodeAndSource, owner, name, descriptor, isInterface);
        }


        @Override
        public void visitLineNumber(int line, Label start) {
            // do nothing
        }


        @Override
        public void visitParameter(String name, int access) {
            // do nothing
        }

        @Override
        @Nullable
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            return null;
        }

        @Override
        @Nullable
        public AnnotationVisitor visitTypeAnnotation(
            int typeRef,
            TypePath typePath,
            String descriptor,
            boolean visible
        ) {
            return null;
        }

        @Override
        public void visitAnnotableParameterCount(int parameterCount, boolean visible) {
            // do nothing
        }

        @Override
        @Nullable
        public AnnotationVisitor visitParameterAnnotation(int parameter, String descriptor, boolean visible) {
            return null;
        }

        @Override
        @Nullable
        public AnnotationVisitor visitInsnAnnotation(
            int typeRef,
            @Nullable TypePath typePath,
            String descriptor,
            boolean visible
        ) {
            return null;
        }

        @Override
        @Nullable
        public AnnotationVisitor visitLocalVariableAnnotation(
            int typeRef,
            @Nullable TypePath typePath,
            @Nullable Label[] start,
            @Nullable Label[] end,
            int[] index,
            String descriptor,
            boolean visible
        ) {
            return null;
        }

        @Override
        @Nullable
        public AnnotationVisitor visitTryCatchAnnotation(
            int typeRef,
            @Nullable TypePath typePath,
            @Nullable String descriptor,
            boolean visible
        ) {
            return null;
        }

    }

    private static class ModuleRegistryCallsClassVisitor extends ClassVisitor {

        private final BiConsumer<String, String> moduleNameConsumer;

        public ModuleRegistryCallsClassVisitor(BiConsumer<String, String> moduleNameConsumer) {
            super(LATEST_ASM_API);
            this.moduleNameConsumer = moduleNameConsumer;
        }

        @Nullable
        private String classInternalName;

        @Override
        public void visit(
            int version,
            int access,
            String name,
            @Nullable String signature,
            @Nullable String superName,
            @Nullable String[] interfaces
        ) {
            super.visit(version, access, name, signature, superName, interfaces);
            this.classInternalName = name;
        }

        @Override
        public MethodVisitor visitMethod(
            int access,
            String name,
            String descriptor,
            @Nullable String signature,
            @Nullable String[] exceptions
        ) {
            return new ModuleRegistryCallsMethodVisitor(
                moduleName -> moduleNameConsumer.accept(requireNonNull(classInternalName), moduleName)
            );
        }

    }

}
