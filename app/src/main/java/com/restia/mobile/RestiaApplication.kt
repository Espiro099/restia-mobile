package com.restia.mobile

import android.app.Application

class RestiaApplication : Application() {
    val container by lazy { AppContainer(this) }
}
