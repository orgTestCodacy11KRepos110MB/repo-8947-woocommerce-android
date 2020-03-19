package com.woocommerce.android.ui.products

import android.content.DialogInterface
import android.net.Uri
import android.os.Parcelable
import android.view.View
import com.squareup.inject.assisted.Assisted
import com.squareup.inject.assisted.AssistedInject
import com.woocommerce.android.R.string
import com.woocommerce.android.analytics.AnalyticsTracker
import com.woocommerce.android.analytics.AnalyticsTracker.Stat
import com.woocommerce.android.analytics.AnalyticsTracker.Stat.PRODUCT_DETAIL_IMAGE_TAPPED
import com.woocommerce.android.annotations.OpenClassOnDebug
import com.woocommerce.android.di.ViewModelAssistedFactory
import com.woocommerce.android.extensions.isEqualTo
import com.woocommerce.android.media.ProductImagesService
import com.woocommerce.android.media.ProductImagesService.Companion.OnProductImageUploaded
import com.woocommerce.android.media.ProductImagesService.Companion.OnProductImagesUpdateCompletedEvent
import com.woocommerce.android.media.ProductImagesService.Companion.OnProductImagesUpdateStartedEvent
import com.woocommerce.android.media.ProductImagesServiceWrapper
import com.woocommerce.android.model.Product
import com.woocommerce.android.model.ShippingClass
import com.woocommerce.android.model.TaxClass
import com.woocommerce.android.tools.NetworkStatus
import com.woocommerce.android.tools.SelectedSite
import com.woocommerce.android.ui.products.ProductDetailViewModel.ProductExitEvent.ExitImages
import com.woocommerce.android.ui.products.ProductDetailViewModel.ProductExitEvent.ExitInventory
import com.woocommerce.android.ui.products.ProductDetailViewModel.ProductExitEvent.ExitPricing
import com.woocommerce.android.ui.products.ProductDetailViewModel.ProductExitEvent.ExitProductDetail
import com.woocommerce.android.ui.products.ProductDetailViewModel.ProductExitEvent.ExitShipping
import com.woocommerce.android.ui.products.ProductNavigationTarget.ExitProduct
import com.woocommerce.android.ui.products.ProductNavigationTarget.ShareProduct
import com.woocommerce.android.ui.products.ProductNavigationTarget.ViewProductImageChooser
import com.woocommerce.android.ui.products.ProductNavigationTarget.ViewProductImages
import com.woocommerce.android.util.CoroutineDispatchers
import com.woocommerce.android.util.CurrencyFormatter
import com.woocommerce.android.util.WooLog
import com.woocommerce.android.util.WooLog.T
import com.woocommerce.android.viewmodel.LiveDataDelegate
import com.woocommerce.android.viewmodel.MultiLiveEvent.Event
import com.woocommerce.android.viewmodel.MultiLiveEvent.Event.Exit
import com.woocommerce.android.viewmodel.MultiLiveEvent.Event.ShowDiscardDialog
import com.woocommerce.android.viewmodel.MultiLiveEvent.Event.ShowSnackbar
import com.woocommerce.android.viewmodel.SavedStateWithArgs
import com.woocommerce.android.viewmodel.ScopedViewModel
import kotlinx.android.parcel.Parcelize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.wordpress.android.fluxc.store.WooCommerceStore
import java.lang.ref.WeakReference
import java.math.BigDecimal
import java.util.Date

@OpenClassOnDebug
class ProductDetailViewModel @AssistedInject constructor(
    @Assisted savedState: SavedStateWithArgs,
    dispatchers: CoroutineDispatchers,
    private val selectedSite: SelectedSite,
    private val productRepository: ProductDetailRepository,
    private val networkStatus: NetworkStatus,
    private val currencyFormatter: CurrencyFormatter,
    private val wooCommerceStore: WooCommerceStore,
    private val productImagesServiceWrapper: ProductImagesServiceWrapper
) : ScopedViewModel(savedState, dispatchers) {
    companion object {
        private const val DEFAULT_DECIMAL_PRECISION = 2
        private const val SEARCH_TYPING_DELAY_MS = 500L
        private const val KEY_PRODUCT_PARAMETERS = "key_product_parameters"
    }

    /**
     * Fetch product related properties (currency, product dimensions) for the site since we use this
     * variable in many different places in the product detail view such as pricing, shipping.
     */
    final val parameters: Parameters by lazy {
        val params = savedState.get<Parameters>(KEY_PRODUCT_PARAMETERS) ?: loadParameters()
        savedState[KEY_PRODUCT_PARAMETERS] = params
        params
    }

    private var skuVerificationJob: Job? = null
    private var shippingClassLoadJob: Job? = null

    // view state for the product detail screen
    final val productDetailViewStateData = LiveDataDelegate(savedState, ProductDetailViewState())
    private var viewState by productDetailViewStateData

    // view state for the product inventory screen
    final val productInventoryViewStateData = LiveDataDelegate(savedState, ProductInventoryViewState())
    private var productInventoryViewState by productInventoryViewStateData

    // view state for the product pricing screen
    final val productPricingViewStateData = LiveDataDelegate(savedState, ProductPricingViewState())
    private var productPricingViewState by productPricingViewStateData

    // view state for the shipping class screen
    final val productShippingClassViewStateData = LiveDataDelegate(savedState, ProductShippingClassViewState())
    private var productShippingClassViewState by productShippingClassViewStateData

    // view state for the product images screen
    final val productImagesViewStateData = LiveDataDelegate(savedState, ProductImagesViewState())
    private var productImagesViewState by productImagesViewStateData

    init {
        EventBus.getDefault().register(this)
    }

    fun getProduct() = viewState

    fun getRemoteProductId() = viewState.product?.remoteId ?: 0L

    fun getTaxClassBySlug(slug: String): TaxClass? {
        return productPricingViewState.taxClassList?.filter { it.slug == slug }?.getOrNull(0)
    }

    fun start(remoteProductId: Long) {
        loadProduct(remoteProductId)
        checkImageUploads(remoteProductId)
    }

    fun initialisePricing() {
        val decimals = wooCommerceStore.getSiteSettings(selectedSite.get())?.currencyDecimalNumber
                ?: DEFAULT_DECIMAL_PRECISION
        productPricingViewState = productPricingViewState.copy(
                currency = parameters.currencyCode,
                decimals = decimals,
                taxClassList = productRepository.getTaxClassesForSite()
        )
    }

    /**
     * Called when the Share menu button is clicked in Product detail screen
     */
    fun onShareButtonClicked() {
        viewState.product?.let {
            triggerEvent(ShareProduct(it.permalink, it.name))
        }
    }

    /**
     * Called when an existing image is selected in Product detail screen
     */
    fun onImageGalleryClicked(image: Product.Image, selectedImage: WeakReference<View>) {
        AnalyticsTracker.track(PRODUCT_DETAIL_IMAGE_TAPPED)
        viewState.product?.let {
            triggerEvent(ViewProductImages(it, image, selectedImage))
        }
    }

    /**
     * Called when the add image icon is clicked in Product detail screen
     */
    fun onAddImageClicked() {
        AnalyticsTracker.track(PRODUCT_DETAIL_IMAGE_TAPPED)
        viewState.product?.let {
            triggerEvent(ViewProductImageChooser(it.remoteId))
        }
    }

    /**
     * Called when the any of the editable sections (such as pricing, shipping, inventory)
     * is selected in Product detail screen
     */
    fun onEditProductCardClicked(target: ProductNavigationTarget) {
        triggerEvent(target)
    }

    /**
     * Called when the DONE menu button is clicked in all of the product sub detail screen
     */
    fun onDoneButtonClicked(event: ProductExitEvent) {
        var eventName: Stat? = null
        var hasChanges = false
        when (event) {
            is ExitInventory -> {
                eventName = Stat.PRODUCT_INVENTORY_SETTINGS_DONE_BUTTON_TAPPED
                hasChanges = viewState.storedProduct?.hasInventoryChanges(viewState.product) ?: false
            }
            is ExitPricing -> {
                eventName = Stat.PRODUCT_PRICE_SETTINGS_DONE_BUTTON_TAPPED
                hasChanges = viewState.storedProduct?.hasPricingChanges(viewState.product) ?: false
            }
            is ExitShipping -> {
                eventName = Stat.PRODUCT_SHIPPING_SETTINGS_DONE_BUTTON_TAPPED
                hasChanges = viewState.storedProduct?.hasShippingChanges(viewState.product) ?: false
            }
            is ExitImages -> {
                // TODO: eventName = ??
                hasChanges = viewState.storedProduct?.hasImageChanges(viewState.product) ?: false
            }
        }
        eventName?.let { AnalyticsTracker.track(it, mapOf(AnalyticsTracker.KEY_HAS_CHANGED_DATA to hasChanges)) }
        triggerEvent(event)
    }

    /**
     * Called when the UPDATE menu button is clicked in the product detail screen.
     * Displays a progress dialog and updates the product
     */
    fun onUpdateButtonClicked() {
        viewState.product?.let {
            viewState = viewState.copy(isProgressDialogShown = true)
            launch { updateProduct(it) }
        }
    }

    /**
     * Method called when back button is clicked.
     *
     * Each product screen has it's own [ProductExitEvent]
     * Based on the exit event, the logic is to check if the discard dialog should be displayed.
     *
     * For all product sub-detail screens such as [ProductInventoryFragment] and [ProductPricingFragment],
     * the discard dialog should only be displayed if there are currently any changes made to the fields in the screen.
     * i.e. viewState.product != viewState.storedProduct and viewState.product != viewState.cachedProduct
     *
     * For the product detail screen, the discard dialog should only be displayed if there are changes to the
     * [Product] model locally, that still need to be saved to the backend. i.e.
     * viewState.product != viewState.storedProduct
     */
    fun onBackButtonClicked(event: ProductExitEvent): Boolean {
        val isProductDetailUpdated = viewState.isProductUpdated
        val isProductSubDetailUpdated = viewState.product?.let { viewState.cachedProduct?.isSameProduct(it) == false }
        val isProductUpdated = when (event) {
            is ExitProductDetail -> isProductDetailUpdated
            else -> isProductDetailUpdated == true && isProductSubDetailUpdated == true
        }
        return if (isProductUpdated == true && event.shouldShowDiscardDialog) {
            triggerEvent(ShowDiscardDialog(
                    positiveBtnAction = DialogInterface.OnClickListener { _, _ ->
                        // discard changes made to the current screen
                        discardEditChanges(event)

                        // If user in Product detail screen, exit product detail,
                        // otherwise, redirect to Product Detail screen
                        if (event is ExitProductDetail) {
                            triggerEvent(ExitProduct)
                        } else {
                            triggerEvent(Exit)
                        }
                    }
            ))
            false
        } else {
            true
        }
    }

    /**
     * Called when user modifies the SKU field. Currently checks if the entered sku is available
     * in the local db. Only if it is not available, the API verification call is initiated.
     */
    fun onSkuChanged(sku: String) {
        // verify if the sku exists only if the text entered by the user does not match the sku stored locally
        if (sku.length > 2 && sku != viewState.storedProduct?.sku) {
            // reset the error message when the user starts typing again
            productInventoryViewState = productInventoryViewState.copy(skuErrorMessage = 0)

            // cancel any existing verification search, then start a new one after a brief delay
            // so we don't actually perform the fetch until the user stops typing
            skuVerificationJob?.cancel()
            skuVerificationJob = launch {
                delay(SEARCH_TYPING_DELAY_MS)

                // check if sku is available from local cache
                if (productRepository.geProductExistsBySku(sku)) {
                    productInventoryViewState = productInventoryViewState.copy(
                            skuErrorMessage = string.product_inventory_update_sku_error
                    )
                } else {
                    verifyProductExistsBySkuRemotely(sku)
                }
            }
        }
    }

    /**
     * Update all product fields that are edited by the user
     */
    fun updateProductDraft(
        description: String? = null,
        title: String? = null,
        sku: String? = null,
        manageStock: Boolean? = null,
        stockStatus: ProductStockStatus? = null,
        soldIndividually: Boolean? = null,
        stockQuantity: String? = null,
        backorderStatus: ProductBackorderStatus? = null,
        regularPrice: BigDecimal? = null,
        salePrice: BigDecimal? = null,
        isSaleScheduled: Boolean? = null,
        dateOnSaleFromGmt: Date? = null,
        dateOnSaleToGmt: Date? = null,
        taxStatus: ProductTaxStatus? = null,
        taxClass: String? = null,
        length: Float? = null,
        width: Float? = null,
        height: Float? = null,
        weight: Float? = null,
        shippingClass: String? = null,
        images: List<Product.Image>? = null
    ) {
        viewState.product?.let { product ->
            val currentProduct = product.copy()
            val updatedProduct = product.copy(
                    description = description ?: product.description,
                    name = title ?: product.name,
                    sku = sku ?: product.sku,
                    manageStock = manageStock ?: product.manageStock,
                    stockStatus = stockStatus ?: product.stockStatus,
                    soldIndividually = soldIndividually ?: product.soldIndividually,
                    backorderStatus = backorderStatus ?: product.backorderStatus,
                    stockQuantity = stockQuantity?.toInt() ?: product.stockQuantity,
                    images = images ?: product.images,
                    regularPrice = if (regularPrice isEqualTo BigDecimal.ZERO) null else regularPrice
                            ?: product.regularPrice,
                    salePrice = if (salePrice isEqualTo BigDecimal.ZERO) null else salePrice ?: product.salePrice,
                    taxStatus = taxStatus ?: product.taxStatus,
                    taxClass = taxClass ?: product.taxClass,
                    length = length ?: product.length,
                    width = width ?: product.width,
                    height = height ?: product.height,
                    weight = weight ?: product.weight,
                    shippingClass = shippingClass ?: product.shippingClass,
                    isSaleScheduled = isSaleScheduled ?: product.isSaleScheduled,
                    dateOnSaleToGmt = if (isSaleScheduled == true ||
                            (isSaleScheduled == null && currentProduct.isSaleScheduled)) {
                        dateOnSaleToGmt ?: product.dateOnSaleToGmt
                    } else viewState.storedProduct?.dateOnSaleToGmt,
                    dateOnSaleFromGmt = if (isSaleScheduled == true ||
                            (isSaleScheduled == null && currentProduct.isSaleScheduled)) {
                        dateOnSaleFromGmt ?: product.dateOnSaleFromGmt
                    } else viewState.storedProduct?.dateOnSaleFromGmt
            )
            viewState = viewState.copy(cachedProduct = currentProduct, product = updatedProduct)

            updateProductEditAction()
        }
    }

    override fun onCleared() {
        super.onCleared()
        productRepository.onCleanup()
        EventBus.getDefault().unregister(this)
    }

    /**
     * Called when discard is clicked on any of the product screens.
     * Resets the changes edited by the user based on [event].
     */
    private fun discardEditChanges(event: ProductExitEvent) {
        val currentProduct = if (event is ExitProductDetail) {
            viewState.storedProduct
        } else viewState.cachedProduct

        when (event) {
            // discard inventory screen changes
            is ExitInventory -> {
                currentProduct?.let {
                    val product = viewState.product?.copy(
                            sku = it.sku,
                            manageStock = it.manageStock,
                            stockStatus = it.stockStatus,
                            backorderStatus = it.backorderStatus,
                            soldIndividually = it.soldIndividually,
                            stockQuantity = it.stockQuantity
                    )
                    viewState = viewState.copy(
                            product = product,
                            cachedProduct = product
                    )
                }
            }
            // discard pricing screen changes
            is ExitPricing -> {
                currentProduct?.let {
                    val product = viewState.product?.copy(
                            dateOnSaleFromGmt = it.dateOnSaleFromGmt,
                            dateOnSaleToGmt = it.dateOnSaleToGmt,
                            isSaleScheduled = it.isSaleScheduled,
                            taxClass = it.taxClass,
                            taxStatus = it.taxStatus,
                            regularPrice = it.regularPrice,
                            salePrice = it.salePrice
                    )
                    viewState = viewState.copy(
                            product = product,
                            cachedProduct = product
                    )
                }
            }
            // discard shipping screen changes
            is ExitShipping -> {
                currentProduct?.let {
                    val product = viewState.product?.copy(
                            weight = it.weight,
                            height = it.height,
                            width = it.width,
                            length = it.length,
                            shippingClass = it.shippingClass
                    )
                    viewState = viewState.copy(
                            product = product,
                            cachedProduct = product
                    )
                }
            }
            // discard product images screen changes
            is ExitImages -> {
                currentProduct?.let {
                    val product = viewState.product?.copy(
                            images = it.images
                    )
                    viewState = viewState.copy(
                            product = product,
                            cachedProduct = product
                    )
                }
            }
        }

        // updates the UPDATE menu button in the product detail screen i.e. the UPDATE menu button
        // will only be displayed if there are changes made to the Product model.
        updateProductEditAction()
    }

    private fun loadProduct(remoteProductId: Long) {
        // Pre-load current site's tax class list for use in the product pricing screen
        launch(dispatchers.main) {
            productRepository.loadTaxClassesForSite()
        }

        launch {
            val productInDb = productRepository.getProduct(remoteProductId)
            if (productInDb != null) {
                updateProductState(productInDb)

                val shouldFetch = remoteProductId != getRemoteProductId()
                val cachedVariantCount = productRepository.getCachedVariantCount(remoteProductId)
                if (shouldFetch || cachedVariantCount != productInDb.numVariations) {
                    fetchProduct(remoteProductId)
                }
            } else {
                viewState = viewState.copy(isSkeletonShown = true)
                fetchProduct(remoteProductId)
            }
            viewState = viewState.copy(isSkeletonShown = false)
        }
    }

    fun reloadProductImages(remoteProductId: Long) {
        val images = productRepository.getProduct(remoteProductId)?.images
        viewState.cachedProduct?.images?.let {
            // TODO
        }
    }

    /**
     * Loads the product dependencies for a site such as dimensions, currency or timezone
     */
    private fun loadParameters(): Parameters {
        val currencyCode = wooCommerceStore.getSiteSettings(selectedSite.get())?.currencyCode
        val gmtOffset = selectedSite.get().timezone?.toFloat() ?: 0f
        val (weightUnit, dimensionUnit) = wooCommerceStore.getProductSettings(selectedSite.get())?.let { settings ->
            return@let Pair(settings.weightUnit, settings.dimensionUnit)
        } ?: Pair(null, null)

        return Parameters(currencyCode, weightUnit, dimensionUnit, gmtOffset)
    }

    private suspend fun fetchProduct(remoteProductId: Long) {
        if (networkStatus.isConnected()) {
            val fetchedProduct = productRepository.fetchProduct(remoteProductId)
            if (fetchedProduct != null) {
                updateProductState(fetchedProduct)
            } else {
                triggerEvent(ShowSnackbar(string.product_detail_fetch_product_error))
                triggerEvent(Exit)
            }
        } else {
            triggerEvent(ShowSnackbar(string.offline_error))
            viewState = viewState.copy(isSkeletonShown = false)
        }
    }

    fun isUploadingImages(remoteProductId: Long) = ProductImagesService.isUploadingForProduct(remoteProductId)

    /**
     * Updates the UPDATE menu button in the product detail screen. UPDATE is only displayed
     * when there are changes made to the [Product] model and this can be verified by comparing
     * the viewState.product with viewState.storedProduct model.
     */
    private fun updateProductEditAction() {
        viewState.product?.let {
            val isProductUpdated = viewState.storedProduct?.isSameProduct(it) == false
            viewState = viewState.copy(isProductUpdated = isProductUpdated)
        }
    }

    fun uploadProductImages(remoteProductId: Long, localUriList: ArrayList<Uri>) {
        if (!networkStatus.isConnected()) {
            triggerEvent(ShowSnackbar(string.network_activity_no_connectivity))
            return
        }
        if (ProductImagesService.isBusy()) {
            triggerEvent(ShowSnackbar(string.product_image_service_busy))
            return
        }
        productImagesServiceWrapper.uploadProductMedia(remoteProductId, localUriList)
    }

    /**
     * Checks whether product images are uploading and ensures the view state reflects any currently
     * uploading images
     */
    private fun checkImageUploads(remoteProductId: Long) {
        val uris = ProductImagesService.getUploadingImageUrisForProduct(remoteProductId)
        viewState = viewState.copy(uploadingImageUris = uris)
        productImagesViewState = productImagesViewState.copy(isUploadingImages = uris.isNotEmpty())
    }

    /**
     * Updates the product to the backend only if network is connected.
     * Otherwise, an offline snackbar is displayed.
     */
    private suspend fun updateProduct(product: Product) {
        if (networkStatus.isConnected()) {
            if (productRepository.updateProduct(product)) {
                triggerEvent(ShowSnackbar(string.product_detail_update_product_success))
                viewState = viewState.copy(product = null, isProductUpdated = false, isProgressDialogShown = false)
                loadProduct(product.remoteId)
            } else {
                triggerEvent(ShowSnackbar(string.product_detail_update_product_error))
                viewState = viewState.copy(isProgressDialogShown = false)
            }
        } else {
            triggerEvent(ShowSnackbar(string.offline_error))
            viewState = viewState.copy(isProgressDialogShown = false)
        }
    }

    private suspend fun verifyProductExistsBySkuRemotely(sku: String) {
        // if the sku is not available display error
        val isSkuAvailable = productRepository.verifySkuAvailability(sku)
        val skuErrorMessage = if (isSkuAvailable == false) {
            string.product_inventory_update_sku_error
        } else 0
        productInventoryViewState = productInventoryViewState.copy(skuErrorMessage = skuErrorMessage)
    }

    /**
     * Load & fetch the shipping classes for the current site, optionally performing a "load more" to
     * load the next page of shipping classes
     */
    fun loadShippingClasses(loadMore: Boolean = false) {
        if (loadMore && !productRepository.canLoadMoreShippingClasses) {
            WooLog.d(T.PRODUCTS, "Can't load more product shipping classes")
            return
        }

        waitForExistingShippingClassFetch()

        shippingClassLoadJob = launch {
            productShippingClassViewState = if (loadMore) {
                productShippingClassViewState.copy(isLoadingMoreProgressShown = true)
            } else {
                // get cached shipping classes and only show loading progress the list is empty, otherwise show
                // them right away
                val cachedShippingClasses = productRepository.getProductShippingClassesForSite()
                if (cachedShippingClasses.isEmpty()) {
                    productShippingClassViewState.copy(isLoadingProgressShown = true)
                } else {
                    productShippingClassViewState.copy(shippingClassList = cachedShippingClasses)
                }
            }

            // fetch shipping classes from the backend
            val shippingClasses = productRepository.fetchShippingClassesForSite(loadMore)
            productShippingClassViewState = productShippingClassViewState.copy(
                    isLoadingProgressShown = false,
                    isLoadingMoreProgressShown = false,
                    shippingClassList = shippingClasses
            )
        }
    }

    /**
     * If shipping classes are already being fetch, wait for the current fetch to complete - this is
     * used above to avoid fetching multiple pages of shipping classes in unison
     */
    private fun waitForExistingShippingClassFetch() {
        if (shippingClassLoadJob?.isActive == true) {
            launch {
                try {
                    shippingClassLoadJob?.join()
                } catch (e: CancellationException) {
                    WooLog.d(
                            T.PRODUCTS,
                            "CancellationException while waiting for existing shipping class list fetch"
                    )
                }
            }
        }
    }

    private fun updateProductState(storedProduct: Product) {
        val updatedProduct = viewState.product?.let {
            if (storedProduct.isSameProduct(it)) storedProduct else storedProduct.mergeProduct(viewState.product)
        } ?: storedProduct

        val weightWithUnits = updatedProduct.getWeightWithUnits(parameters.weightUnit)
        val sizeWithUnits = updatedProduct.getSizeWithUnits(parameters.dimensionUnit)

        viewState = viewState.copy(
                product = updatedProduct,
                cachedProduct = viewState.cachedProduct ?: updatedProduct,
                storedProduct = storedProduct,
                weightWithUnits = weightWithUnits,
                sizeWithUnits = sizeWithUnits,
                priceWithCurrency = formatCurrency(updatedProduct.price, parameters.currencyCode),
                salePriceWithCurrency = formatCurrency(updatedProduct.salePrice, parameters.currencyCode),
                regularPriceWithCurrency = formatCurrency(updatedProduct.regularPrice, parameters.currencyCode),
                gmtOffset = parameters.gmtOffset
        )
    }

    private fun formatCurrency(amount: BigDecimal?, currencyCode: String?): String {
        return currencyCode?.let {
            currencyFormatter.formatCurrency(amount ?: BigDecimal.ZERO, it)
        } ?: amount.toString()
    }

    /**
     * The list of product images has started uploading
     */
    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onEventMainThread(event: OnProductImagesUpdateStartedEvent) {
        checkImageUploads(event.remoteProductId)
    }

    /**
     * The list of product images has finished uploading
     */
    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onEventMainThread(event: OnProductImagesUpdateCompletedEvent) {
        loadProduct(event.remoteProductId)
        checkImageUploads(event.remoteProductId)
    }

    /**
     * A single product image has finished uploading
     */
    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onEventMainThread(event: OnProductImageUploaded) {
        if (event.isError) {
            triggerEvent(ShowSnackbar(string.product_image_service_error_uploading))
        } else {
            loadProduct(event.remoteProductId)
        }
        checkImageUploads(event.remoteProductId)
    }

    /**
     * Sealed class that handles the back navigation for the product detail screens while providing a common
     * interface for managing them as a single type. Currently used in all the product sub detail screens when
     * back is clicked or DONE is clicked.
     *
     * Add a new class here for each new product sub detail screen to handle back navigation.
     */
    sealed class ProductExitEvent(val shouldShowDiscardDialog: Boolean = true) : Event() {
        class ExitProductDetail(shouldShowDiscardDialog: Boolean = true) : ProductExitEvent(shouldShowDiscardDialog)
        class ExitInventory(shouldShowDiscardDialog: Boolean = true) : ProductExitEvent(shouldShowDiscardDialog)
        class ExitPricing(shouldShowDiscardDialog: Boolean = true) : ProductExitEvent(shouldShowDiscardDialog)
        class ExitShipping(shouldShowDiscardDialog: Boolean = true) : ProductExitEvent(shouldShowDiscardDialog)
        class ExitImages(shouldShowDiscardDialog: Boolean = true) : ProductExitEvent(shouldShowDiscardDialog)
    }

    @Parcelize
    data class Parameters(
        val currencyCode: String?,
        val weightUnit: String?,
        val dimensionUnit: String?,
        val gmtOffset: Float
    ) : Parcelable

    /**
     * [product] is used for the UI. Any updates to the fields in the UI would update this model.
     * [cachedProduct] is a copy of the [product] model before a change has been made to the [product] model.
     * [storedProduct] is the [Product] model that is fetched from the API and available in the local db.
     * This is read only and is not updated in any way. It is used in the product detail screen, to check
     * if we need to display the UPDATE menu button (which is only displayed if there are changes made to
     * any of the product fields).
     *
     * [isProductUpdated] is used to determine if there are any changes made to the product by comparing
     * [product] and [storedProduct]. Currently used in the product detail screen to display or hide the UPDATE
     * menu button.
     *
     * When the user first enters the product detail screen, the [product] , [storedProduct]  and [cachedProduct] 
     * are the same. When a change is made to the product in the UI,
     * 1. the [cachedProduct] is updated with the [product] model first, then
     * 2. the [product] model is updated with whatever change has been made in the UI.
     *
     * The [cachedProduct] keeps track of the changes made to the [product] in order to discard the changes
     * when necessary.
     *
     */
    @Parcelize
    data class ProductDetailViewState(
        val product: Product? = null,
        var cachedProduct: Product? = null,
        var storedProduct: Product? = null,
        val isSkeletonShown: Boolean? = null,
        val uploadingImageUris: List<Uri>? = null,
        val isProgressDialogShown: Boolean? = null,
        val weightWithUnits: String? = null,
        val sizeWithUnits: String? = null,
        val priceWithCurrency: String? = null,
        val salePriceWithCurrency: String? = null,
        val regularPriceWithCurrency: String? = null,
        val isProductUpdated: Boolean? = null,
        val gmtOffset: Float = 0f
    ) : Parcelable

    @Parcelize
    data class ProductInventoryViewState(
        val skuErrorMessage: Int? = null
    ) : Parcelable

    @Parcelize
    data class ProductPricingViewState(
        val currency: String? = null,
        val decimals: Int = DEFAULT_DECIMAL_PRECISION,
        val taxClassList: List<TaxClass>? = null
    ) : Parcelable

    @Parcelize
    data class ProductShippingClassViewState(
        val isLoadingProgressShown: Boolean = false,
        val isLoadingMoreProgressShown: Boolean = false,
        val shippingClassList: List<ShippingClass>? = null
    ) : Parcelable

    @Parcelize
    data class ProductImagesViewState(
        val isUploadingImages: Boolean = false
    ) : Parcelable

    @AssistedInject.Factory
    interface Factory : ViewModelAssistedFactory<ProductDetailViewModel>
}
