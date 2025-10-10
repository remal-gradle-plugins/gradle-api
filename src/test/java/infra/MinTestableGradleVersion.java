package infra;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import org.gradle.util.GradleVersion;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Minimum base {@link GradleVersion} to run the annotated test(s).
 */
@ExtendWith(MinTestableGradleVersionExtension.class)
@Target({TYPE, METHOD})
@Retention(RUNTIME)
@Inherited
@Documented
public @interface MinTestableGradleVersion {

    String value();

    String reason();

}
