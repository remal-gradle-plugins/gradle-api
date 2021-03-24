package build.collect

import build.BaseProjectPlugin

class CollectGradleApiPlugin extends BaseProjectPlugin {

    static final String COLLECT_GRADLE_API_INFO_TASK_NAME = 'collectGradleApiInfo'

    @Override
    protected void applyImpl() {
        tasks.register(COLLECT_GRADLE_API_INFO_TASK_NAME, CollectGradleApiInfo)
    }

}
