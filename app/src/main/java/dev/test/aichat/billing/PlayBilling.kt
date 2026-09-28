package dev.test.aichat.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.*
import dev.test.aichat.data.ChatRepository
import dev.test.aichat.data.friendlyError
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.resume

data class Plan(val id: String, val title: String, val price: String, val period: String, val details: ProductDetails? = null, val offerToken: String = "") {
    val isPreview: Boolean get() = details == null
}
val previewPlans = listOf(
    Plan("chat_weekly", "Неделя", "499 ₽", "за неделю"),
    Plan("chat_monthly", "Месяц", "1 290 ₽", "за месяц")
)
data class BillingState(val loading: Boolean = false, val plans: List<Plan> = emptyList(), val error: String? = null, val notice: String? = null)
class PlayBilling(context: Context, private val repository: ChatRepository, private val scope: CoroutineScope) : PurchasesUpdatedListener {
    private val _state = MutableStateFlow(BillingState())
    val state = _state.asStateFlow()
    private val client = BillingClient.newBuilder(context.applicationContext)
        .setListener(this).enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()).build()
    private val connectionMutex = kotlinx.coroutines.sync.Mutex()
    private suspend fun connect() {
        connectionMutex.lock()
        try {
            if (client.isReady) return
            suspendCancellableCoroutine { cont ->
                client.startConnection(object : BillingClientStateListener {
                    override fun onBillingSetupFinished(result: BillingResult) {
                        if (!cont.isActive) return
                        if (result.responseCode == BillingClient.BillingResponseCode.OK) cont.resume(Unit)
                        else cont.resumeWith(Result.failure(IllegalStateException("Не удалось подключиться к Google Play. Проверьте интернет и аккаунт магазина.")))
                    }
                    override fun onBillingServiceDisconnected() { }
                })
            }
        } finally { connectionMutex.unlock() }
    }
    suspend fun load() {
        _state.value = _state.value.copy(loading = true, error = null, notice = null)
        try {
            withTimeout(20000) {
                connect()
                val products = listOf("chat_weekly", "chat_monthly").map { QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(BillingClient.ProductType.SUBS).build() }
                val details = suspendCancellableCoroutine<List<ProductDetails>> { cont ->
                    client.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(products).build()) { result, items ->
                        if (cont.isActive) {
                            if (result.responseCode == BillingClient.BillingResponseCode.OK) cont.resume(items.productDetailsList)
                            else cont.resumeWith(Result.failure(IllegalStateException("Не удалось получить цены Google Play")))
                        }
                    }
                }
                val plans = details.mapNotNull { product ->
                    val offer = product.subscriptionOfferDetails?.firstOrNull { it.offerId == null } ?: return@mapNotNull null
                    val price = offer.pricingPhases.pricingPhaseList.lastOrNull() ?: return@mapNotNull null
                    Plan(product.productId, if (product.productId == "chat_weekly") "Неделя" else "Месяц", price.formattedPrice,
                        if (price.billingPeriod == "P1W") "за неделю" else "за месяц", product, offer.offerToken)
                }.sortedBy { if (it.id == "chat_weekly") 0 else 1 }
                _state.value = BillingState(plans = plans.ifEmpty { previewPlans })
            }
        } catch (e: Exception) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            _state.value = _state.value.copy(loading = false, plans = previewPlans, error = null)
        }
    }
    fun purchase(activity: Activity, plan: Plan) {
        _state.value = _state.value.copy(error = null, notice = null)
        val details = plan.details ?: run {
            _state.value = _state.value.copy(notice = "Это тестовые цены. Оплата появится после подключения Google Play.")
            return
        }
        if (!client.isReady) { _state.value = _state.value.copy(error = "Нет соединения с Google Play. Обновите тарифы."); return }
        val params = BillingFlowParams.newBuilder()
            .setObfuscatedAccountId(repository.auth.store.deviceId)
            .setProductDetailsParamsList(listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details).setOfferToken(plan.offerToken).build()))
            .build()
        val result = client.launchBillingFlow(activity, params)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) _state.value = _state.value.copy(error = "Не удалось открыть оплату Google Play")
    }
    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        if (result.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) return
        if (result.responseCode != BillingClient.BillingResponseCode.OK) { _state.value = _state.value.copy(error = "Google Play не завершил покупку"); return }
        scope.launch { process(purchases.orEmpty(), showEmpty = false) }
    }
    suspend fun restore(silent: Boolean = false) {
        if (!silent) _state.value = _state.value.copy(loading = true, error = null, notice = null)
        try {
            withTimeout(20000) {
                connect()
                val purchases = suspendCancellableCoroutine<List<Purchase>> { cont ->
                    client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()) { result, items ->
                        if (cont.isActive) {
                            if (result.responseCode == BillingClient.BillingResponseCode.OK) cont.resume(items)
                            else cont.resumeWith(Result.failure(IllegalStateException("Не удалось восстановить покупки")))
                        }
                    }
                }
                process(purchases, showEmpty = !silent)
            }
        } catch (e: Exception) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            if (!silent) _state.value = _state.value.copy(loading = false, error = e.message ?: "Восстановление недоступно")
        }
    }
    private suspend fun process(purchases: List<Purchase>, showEmpty: Boolean) {
        val active = purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED && it.products.any { id -> id in listOf("chat_weekly", "chat_monthly") } }
        try {
            active.forEach { purchase ->
                repository.verifyPurchase(purchase.purchaseToken)
                if (!purchase.isAcknowledged) suspendCancellableCoroutine { cont ->
                    client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()) { result ->
                        if (cont.isActive) {
                            if (result.responseCode == BillingClient.BillingResponseCode.OK) cont.resume(Unit)
                            else cont.resumeWith(Result.failure(IllegalStateException("Подписка проверена, подтверждение Google Play не завершено. Повторите восстановление.")))
                        }
                    }
                }
            }
            val pending = purchases.any { it.purchaseState == Purchase.PurchaseState.PENDING }
            _state.value = _state.value.copy(loading = false, notice = when {
                active.isNotEmpty() -> "Подписка восстановлена"
                pending -> "Покупка ожидает подтверждения Google Play"
                showEmpty -> "Активные покупки не найдены"
                else -> _state.value.notice
            })
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _state.value = _state.value.copy(loading = false, error = friendlyError(e))
        }
    }
}
