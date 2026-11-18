package com.restia.mobile

import android.content.Context
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.util.DebugLogger
import com.restia.mobile.core.network.BearerInterceptor
import com.restia.mobile.core.network.CertificatePinning
import com.restia.mobile.core.network.ContactInterceptor
import com.restia.mobile.core.network.ImageClient
import com.restia.mobile.core.network.PublicAuthApi
import com.restia.mobile.core.network.RequestIdInterceptor
import com.restia.mobile.core.network.SessionApi
import com.restia.mobile.core.network.TokenAuthenticator
import com.restia.mobile.core.network.ApiJson
import com.restia.mobile.core.security.AccessTokenHolder
import com.restia.mobile.core.security.CredentialStore
import com.restia.mobile.core.security.EncryptedFileStore
import com.restia.mobile.core.security.KeystoreCredentialStore
import com.restia.mobile.core.storage.BlobStore
import com.restia.mobile.core.storage.ContactTracker
import com.restia.mobile.data.account.PasswordRepository
import com.restia.mobile.data.auth.AuthRepository
import com.restia.mobile.data.auth.AuthStateHolder
import com.restia.mobile.data.auth.TokenRefresher
import com.restia.mobile.data.cash.CashRepository
import com.restia.mobile.data.checks.CheckRepository
import com.restia.mobile.data.kitchen.KitchenRepository
import com.restia.mobile.data.customers.CustomersRepository
import com.restia.mobile.data.members.MembersRepository
import com.restia.mobile.data.orders.DraftRepository
import com.restia.mobile.data.account.SessionsRepository
import com.restia.mobile.data.notifications.NotificationsRepository
import com.restia.mobile.data.printing.PrintingRepository
import com.restia.mobile.data.reservations.ReservationsRepository
import com.restia.mobile.data.orders.MenuRepository
import com.restia.mobile.data.orders.OrderRepository
import com.restia.mobile.data.tables.TablesRepository
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Une las dependencias. No registra cuerpos de peticiones: llevan contraseñas y tokens. */
class Repositories(
    val auth: AuthRepository,
    val tables: TablesRepository,
    val menu: MenuRepository,
    val orders: OrderRepository,
    val drafts: DraftRepository,
    val contact: ContactTracker,
    val kitchen: KitchenRepository,
    val checks: CheckRepository,
    val reservations: ReservationsRepository,
    val customers: CustomersRepository,
    val members: MembersRepository,
    val passwords: PasswordRepository,
    val cash: CashRepository,
    val printing: PrintingRepository,
    val notifications: NotificationsRepository,
    val sessions: SessionsRepository,
)

fun buildRepositories(
    baseUrl: String,
    store: CredentialStore,
    blobs: BlobStore,
    certificatePins: List<String> = emptyList(),
    pinsRequired: Boolean = false,
): Repositories {
    val accessToken = AccessTokenHolder()
    val state = AuthStateHolder()
    val converter = ApiJson.asConverterFactory("application/json".toMediaType())
    // Todas las llamadas (públicas y con sesión) salen de este cliente, así que todas llevan el anclaje.
    val base = CertificatePinning.apply(OkHttpClient.Builder(), baseUrl, certificatePins, pinsRequired)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor(RequestIdInterceptor())
        .build()
    val publicApi = Retrofit.Builder().baseUrl(baseUrl).client(base).addConverterFactory(converter).build()
        .create(PublicAuthApi::class.java)
    val refresher = TokenRefresher(store, accessToken, publicApi, state)
    val contact = ContactTracker(blobs)
    val sessionClient = base.newBuilder()
        .addInterceptor(BearerInterceptor(accessToken, refresher))
        .addInterceptor(ContactInterceptor(contact))
        .authenticator(TokenAuthenticator(refresher))
        .build()
    val sessionApi = Retrofit.Builder().baseUrl(baseUrl).client(sessionClient).addConverterFactory(converter).build()
        .create(SessionApi::class.java)
    return Repositories(
        AuthRepository(store, accessToken, publicApi, sessionApi, refresher, state, blobs),
        TablesRepository(sessionApi, blobs),
        MenuRepository(sessionApi, blobs),
        OrderRepository(sessionApi),
        DraftRepository(blobs),
        contact,
        KitchenRepository(sessionApi),
        CheckRepository(sessionApi),
        ReservationsRepository(sessionApi),
        CustomersRepository(sessionApi),
        MembersRepository(sessionApi),
        PasswordRepository(publicApi, sessionApi),
        CashRepository(sessionApi),
        PrintingRepository(sessionApi),
        NotificationsRepository(sessionApi),
        SessionsRepository(sessionApi),
    )
}

class AppContainer(context: Context) {
    private val blobs = EncryptedFileStore(context.applicationContext)
    private val pins = CertificatePinning.parse(BuildConfig.API_CERT_PINS)
    val repositories = buildRepositories(
        BuildConfig.API_BASE_URL,
        KeystoreCredentialStore(context.applicationContext, blobs),
        blobs,
        certificatePins = pins,
        // En release, sin HTTPS o sin huellas la app no se conecta (falla cerrado).
        pinsRequired = !BuildConfig.DEBUG,
    )

    /** Cargador de las fotos del menú: su propio cliente, sin la sesión. */
    val imageLoader: ImageLoader by lazy {
        val client = ImageClient.build(BuildConfig.API_BASE_URL, pins, pinsRequired = !BuildConfig.DEBUG)
        ImageLoader.Builder(context.applicationContext)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
            // En debug, por qué no cargó una foto (Logcat, etiqueta del cargador).
            .apply { if (BuildConfig.DEBUG) logger(DebugLogger()) }
            .build()
    }
}
