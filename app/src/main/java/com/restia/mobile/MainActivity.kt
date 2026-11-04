package com.restia.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.restia.mobile.data.auth.AuthState
import com.restia.mobile.ui.BranchHome
import com.restia.mobile.ui.auth.AuthViewModel
import com.restia.mobile.ui.auth.EnrollScreen
import com.restia.mobile.ui.auth.LoadingScreen
import com.restia.mobile.ui.auth.LoginScreen
import com.restia.mobile.ui.auth.OfflineScreen
import com.restia.mobile.ui.theme.RestiaTheme

class MainActivity : ComponentActivity() {
    private val repositories get() = (application as RestiaApplication).container.repositories

    private val viewModel: AuthViewModel by viewModels {
        AuthViewModel.Factory(repositories.auth)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RestiaTheme {
                val state by viewModel.authState.collectAsState()
                val form by viewModel.form.collectAsState()
                val notice by viewModel.notice.collectAsState()
                when (val current = state) {
                    AuthState.Loading -> LoadingScreen()
                    AuthState.Offline -> OfflineScreen(viewModel::start)
                    AuthState.NeedsEnrollment -> EnrollScreen(form, notice, viewModel::enroll)
                    AuthState.NeedsLogin -> LoginScreen(form, notice, viewModel::login)
                    is AuthState.Authenticated -> {
                        val membership = current.me.memberships.firstOrNull { it.branch.id == current.branchId }
                        BranchHome(
                            repositories = repositories,
                            userId = current.me.user.id,
                            branchId = current.branchId,
                            branchName = membership?.branch?.name ?: current.me.user.name,
                            role = membership?.role.orEmpty(),
                            permissions = membership?.permissions.orEmpty(),
                            logoutEnabled = !form.busy,
                            onLogout = viewModel::logout,
                        )
                    }
                }
            }
        }
    }
}
