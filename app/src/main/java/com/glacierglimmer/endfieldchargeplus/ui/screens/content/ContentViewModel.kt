package com.glacierglimmer.endfieldchargeplus.ui.screens.content

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.glacierglimmer.endfieldchargeplus.core.i18n.UiLanguage
import com.glacierglimmer.endfieldchargeplus.core.metrics.MetricSnapshot
import com.glacierglimmer.endfieldchargeplus.core.model.AnimationMode
import com.glacierglimmer.endfieldchargeplus.core.model.AppConfig
import com.glacierglimmer.endfieldchargeplus.core.model.HudProfile
import com.glacierglimmer.endfieldchargeplus.core.model.HudRenderData
import com.glacierglimmer.endfieldchargeplus.di.EcpContainer
import com.glacierglimmer.endfieldchargeplus.metrics.MetricDemand
import com.glacierglimmer.endfieldchargeplus.ui.state.ProfileEdits
import com.glacierglimmer.endfieldchargeplus.ui.state.VariableProjection
import com.glacierglimmer.endfieldchargeplus.ui.state.VariableRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Result feedback of a scheme edit. */
enum class ContentMessage {
    SAVED,
    SAVED_AS_COPY,
    NOTHING_CHANGED,
    BUILT_IN_READ_ONLY,
    CREATED,
    DELETED,
}

/**
 * HUD content page state.
 *
 * The editor works on a *draft* copy of the selected scheme: typing never touches the persisted
 * configuration, and Save commits through `configRepository.update`. This is also what makes
 * "editing a built-in scheme creates a copy" work without mutating the preset.
 */
class ContentViewModel(private val container: EcpContainer) : ViewModel() {

    val config: StateFlow<AppConfig> = container.configRepository.config

    val snapshot: StateFlow<MetricSnapshot> = container.metricRepository.snapshot

    private val _draft = MutableStateFlow<HudProfile?>(null)
    val draft: StateFlow<HudProfile?> = _draft.asStateFlow()

    private val _selectedId = MutableStateFlow(container.configRepository.config.value.customHud.activeProfileId)
    val selectedId: StateFlow<String> = _selectedId.asStateFlow()

    private val _message = MutableStateFlow<ContentMessage?>(null)
    val message: StateFlow<ContentMessage?> = _message.asStateFlow()

    private var samplingRequested = false

    init {
        loadDraft()
    }

    fun consumeMessage() {
        _message.value = null
    }

    // ------------------------------------------------------------------ sampling demand

    /**
     * The preview needs live values, so the sampler is told that a foreground UI is looking at it.
     * Nothing is stopped on leave: the demand simply drops back to idle and the sampler throttles
     * itself, which is exactly what [MetricDemand] is for.
     */
    fun onScreenVisible() {
        container.metricRepository.setDemand(
            MetricDemand(
                outputActive = false,
                hudVisible = false,
                foregroundUi = true,
                screenOn = true,
            ),
        )
        if (!container.metricRepository.running.value) {
            runCatching { container.metricRepository.start() }
            samplingRequested = true
        }
        container.metricRepository.setActiveProfile(_draft.value)
        container.metricRepository.refreshNow()
    }

    fun onScreenHidden() {
        container.metricRepository.setDemand(MetricDemand.Idle)
        container.metricRepository.setActiveProfile(config.value.customHud.activeProfile())
        samplingRequested = false
    }

    fun samplingWasRequested(): Boolean = samplingRequested

    // ------------------------------------------------------------------ selection & draft

    fun selectProfile(profileId: String) {
        _selectedId.value = profileId
        loadDraft()
    }

    fun loadDraft() {
        val current = config.value
        val selected = current.customHud.profileById(_selectedId.value)
            ?: current.customHud.activeProfile()
        _draft.value = selected?.copy(colorRules = selected.colorRules.toList())
        if (selected != null) _selectedId.value = selected.id
    }

    fun updateDraft(transform: (HudProfile) -> HudProfile) {
        _draft.value = _draft.value?.let(transform)
    }

    /** Re-reads the stored profile into the draft, discarding unsaved edits. */
    fun revertDraft() = loadDraft()

    fun draftChanged(): Boolean {
        val stored = config.value.customHud.profileById(_selectedId.value) ?: return false
        val current = _draft.value ?: return false
        return !ProfileEdits.functionallyEquals(stored, current) || stored.name != current.name
    }

    // ------------------------------------------------------------------ scheme management

    /**
     * Commits the draft.
     *
     * @param enteredName the name typed by the user (for a built-in this becomes the copy's name).
     * @param modifiedSuffix appended to the built-in display name when the user did not rename.
     * @param customFallbackName used when a custom scheme is saved with an empty name.
     */
    fun save(enteredName: String, modifiedSuffix: String, customFallbackName: String) {
        val stored = config.value.customHud.profileById(_selectedId.value) ?: return
        val current = _draft.value ?: return
        val name = enteredName.trim()

        if (ProfileEdits.isBuiltIn(stored)) {
            val renamed = name.isNotEmpty() && name != stored.name
            val changed = renamed || !ProfileEdits.functionallyEquals(stored, current)
            if (!changed) {
                _message.value = ContentMessage.NOTHING_CHANGED
                return
            }
            val copyName = if (renamed) name else stored.name + modifiedSuffix
            update { ProfileEdits.saveBuiltInAsCustom(it, stored.id, current, copyName) }
            _message.value = ContentMessage.SAVED_AS_COPY
        } else {
            val finalName = name.ifEmpty { customFallbackName }
            val changed = finalName != stored.name || !ProfileEdits.functionallyEquals(stored, current)
            if (!changed) {
                _message.value = ContentMessage.NOTHING_CHANGED
                return
            }
            update { config ->
                ProfileEdits.updateProfile(config, stored.id) { current.copy(name = finalName) }
            }
            _message.value = ContentMessage.SAVED
        }
        loadDraft()
    }

    fun setActiveProfile() {
        val id = _selectedId.value
        update { config -> config.copy(customHud = config.customHud.copy(activeProfileId = id)) }
        container.metricRepository.setActiveProfile(config.value.customHud.profileById(id))
    }

    fun createCustom(name: String, titleTemplate: String) {
        update { ProfileEdits.createCustom(it, name = name, titleTemplate = titleTemplate) }
        _message.value = ContentMessage.CREATED
        loadDraft()
    }

    fun duplicate(name: String) {
        update { ProfileEdits.duplicate(it, _selectedId.value, name) }
        _message.value = ContentMessage.CREATED
        loadDraft()
    }

    fun delete() {
        val stored = config.value.customHud.profileById(_selectedId.value) ?: return
        if (ProfileEdits.isBuiltIn(stored)) {
            _message.value = ContentMessage.BUILT_IN_READ_ONLY
            return
        }
        update { ProfileEdits.deleteProfile(it, stored.id) }
        _message.value = ContentMessage.DELETED
        loadDraft()
    }

    fun rename(name: String) {
        update { ProfileEdits.rename(it, _selectedId.value, name) }
        _message.value = ContentMessage.SAVED
        loadDraft()
    }

    // ------------------------------------------------------------------ carousel

    fun setAutoCycle(enabled: Boolean) = update { config ->
        config.copy(customHud = config.customHud.copy(autoCycle = enabled))
    }

    fun setCycleSeconds(seconds: Int) = update { config ->
        config.copy(customHud = config.customHud.copy(cycleSeconds = seconds.coerceIn(3, 3600)))
    }

    fun setCycleAnimationMode(mode: AnimationMode) = update { config ->
        config.copy(customHud = config.customHud.copy(cycleAnimationMode = mode.wire))
    }

    fun addCycleEntry(profileId: String) = update { ProfileEdits.addCycleEntry(it, profileId) }

    fun removeCycleEntry(index: Int) = update { ProfileEdits.removeCycleEntry(it, index) }

    fun moveCycleEntry(index: Int, delta: Int) = update { ProfileEdits.moveCycleEntry(it, index, delta) }

    // ------------------------------------------------------------------ preview & registry

    /** Render data for the live preview, produced by the same builder the HUD uses. */
    fun render(profile: HudProfile, snapshot: MetricSnapshot, language: UiLanguage): HudRenderData =
        container.hudStateBuilder.build(profile, snapshot, language)

    /** Accent colour after colour-rule evaluation, for the editor preview swatch. */
    fun accentColor(profile: HudProfile, snapshot: MetricSnapshot): String =
        container.hudStateBuilder.resolveAccent(profile, snapshot)

    fun variableRows(snapshot: MetricSnapshot, language: UiLanguage): List<VariableRow> =
        VariableProjection.rows(snapshot, language)

    private fun update(transform: (AppConfig) -> AppConfig) {
        viewModelScope.launch { container.configRepository.update(transform) }
    }

    companion object {
        fun factory(container: EcpContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { ContentViewModel(container) }
        }
    }
}
