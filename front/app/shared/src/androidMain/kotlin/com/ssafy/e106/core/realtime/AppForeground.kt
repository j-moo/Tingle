package com.ssafy.e106.core.realtime

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 앱의 Activity 가 하나라도 보이는지(started). 콜백은 메인 스레드에서만 와서 잠금이 필요 없다. 앱 시작 때 만들어야 첫 Activity 를 놓치지 않는다 */
internal fun appForeground(app: Application): StateFlow<Boolean> {
    val visible = MutableStateFlow(false)
    var started = 0
    app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) { visible.value = ++started > 0 }
        override fun onActivityStopped(activity: Activity) { started = maxOf(0, started - 1); visible.value = started > 0 }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    })
    return visible
}
