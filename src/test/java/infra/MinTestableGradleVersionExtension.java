package infra;

import static java.lang.String.format;
import static org.junit.jupiter.api.extension.ConditionEvaluationResult.disabled;
import static org.junit.jupiter.api.extension.ConditionEvaluationResult.enabled;
import static org.junit.platform.commons.util.AnnotationUtils.findAnnotation;

import org.gradle.util.GradleVersion;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

public class MinTestableGradleVersionExtension implements ExecutionCondition {

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        MinTestableGradleVersion annotation = findAnnotation(
            context.getElement(),
            MinTestableGradleVersion.class
        ).orElse(null);

        if (annotation == null) {
            return enabled(format("@%s is not present", MinTestableGradleVersion.class.getSimpleName()));
        }

        GradleVersion minGradleVersion = GradleVersion.version(annotation.value()).getBaseVersion();
        GradleVersion currentGradleVersion = GradleVersion.current().getBaseVersion();
        if (currentGradleVersion.compareTo(minGradleVersion) >= 0) {
            return enabled(format(
                "Current Gradle version %s is greater or equal to min testable version %s",
                currentGradleVersion.getVersion(),
                minGradleVersion.getVersion()
            ));
        } else {
            return disabled(format(
                "Current Gradle version %s is less than min testable version %s. Reason: %s.",
                currentGradleVersion.getVersion(),
                minGradleVersion.getVersion(),
                annotation.reason()
            ));
        }
    }

}
