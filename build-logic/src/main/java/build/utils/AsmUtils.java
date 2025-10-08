package build.utils;

import static build.utils.Utils.substringBeforeLast;
import static java.lang.Integer.parseInt;
import static java.lang.reflect.Modifier.isStatic;
import static java.util.Arrays.stream;
import static java.util.Comparator.comparingInt;

import java.io.InputStream;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import javax.annotation.WillNotClose;
import lombok.SneakyThrows;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

public abstract class AsmUtils {

    private static final Pattern ASM_API_FIELD_NAME = Pattern.compile("^ASM(\\d+)$");

    @SneakyThrows
    private static int getLatestAsmApi() {
        var latestAsmApiField = stream(Opcodes.class.getFields())
            .filter(field -> !field.isSynthetic())
            .filter(field -> isStatic(field.getModifiers()))
            .filter(field ->
                field.getType() == int.class
                    && ASM_API_FIELD_NAME.matcher(field.getName()).matches()
            )
            .max(comparingInt(field -> {
                var matcher = ASM_API_FIELD_NAME.matcher(field.getName());
                if (!matcher.matches()) {
                    throw new AssertionError("unreachable");
                }

                var apiStr = matcher.group(1);
                var apiVersion = parseInt(apiStr);
                return apiVersion;
            }))
            .orElseThrow(() -> new IllegalStateException("Latest ASM API field not found"));

        return (Integer) latestAsmApiField.get(null);
    }

    public static final int LATEST_ASM_API = getLatestAsmApi();


    @Nullable
    @SneakyThrows
    public static String getSourceFile(@WillNotClose InputStream in) {
        var dirRef = new AtomicReference<@Nullable String>();
        var fileNameRef = new AtomicReference<@Nullable String>();

        var classReader = new ClassReader(in);
        classReader.accept(new ClassVisitor(LATEST_ASM_API) {
            @Override
            public void visit(
                int version,
                int access,
                String name,
                @Nullable String signature,
                @Nullable String superName,
                @Nullable String[] interfaces
            ) {
                dirRef.set(substringBeforeLast(name, '/'));
            }

            @Override
            public void visitSource(String source, String debug) {
                fileNameRef.set(source);
            }
        }, 0);

        var fileName = fileNameRef.get();
        if (fileName == null || fileName.isEmpty()) {
            return null;
        }

        var dir = dirRef.get();
        if (dir != null && !dir.isEmpty()) {
            return dir + '/' + fileName;
        } else {
            return fileName;
        }
    }

}
