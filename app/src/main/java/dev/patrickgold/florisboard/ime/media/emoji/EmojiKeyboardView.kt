/*
 * Copyright (C) 2020 Patrick Goldinger
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.media.emoji

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.*
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.*
import com.google.android.material.tabs.TabLayout
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.ime.core.FlorisBoard
import dev.patrickgold.florisboard.ime.theme.Theme
import dev.patrickgold.florisboard.ime.theme.ThemeManager
import kotlinx.coroutines.*
import java.util.*

/**
 * Manages the layout creation and touch events for the emoji section of the media context. Parts
 * of the layout of this view will be generated in coroutines and will therefore not instantly be
 * visible.
 *
 * @property florisboard Reference to instance of core class [FlorisBoard].
 */
class EmojiKeyboardView : LinearLayout, FlorisBoard.EventListener,
    ThemeManager.OnThemeUpdatedListener {
    private val florisboard: FlorisBoard? = FlorisBoard.getInstanceOrNull()
    private val themeManager: ThemeManager = ThemeManager.default()

    private var activeCategory: EmojiCategory = EmojiCategory.SMILEYS_EMOTION
    private var emojiViewFlipper: ViewFlipper
    val emojiKeyWidth = resources.getDimension(R.dimen.emoji_key_width).toInt()
    val emojiKeyHeight = resources.getDimension(R.dimen.emoji_key_height).toInt()
    private var layouts: Deferred<EmojiLayoutDataMap>
    private val mainScope = MainScope()
    private val tabLayout: TabLayout
    private val uiLayouts = EnumMap<EmojiCategory, RecyclerView>(EmojiCategory::class.java)
    private val layoutAdapters = EnumMap<EmojiCategory, EmojiKeyAdapter>(EmojiCategory::class.java)

    var isScrollBlocked: Boolean = false

    constructor(context: Context) : this(context, null)
    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        layouts = mainScope.async(Dispatchers.IO) {
            parseRawEmojiSpecsFile(context, "ime/media/emoji/emoji-test.txt")
        }
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        )
        orientation = VERTICAL

        emojiViewFlipper = ViewFlipper(context)
        emojiViewFlipper.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0).apply {
            weight = 1.0f
        }
        emojiViewFlipper.measureAllChildren = false
        addView(emojiViewFlipper)

        tabLayout =
            ViewGroup.inflate(context, R.layout.media_input_emoji_tabs, null) as TabLayout
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                setActiveCategory(
                    when (tab?.position) {
                        0 -> EmojiCategory.SMILEYS_EMOTION
                        1 -> EmojiCategory.PEOPLE_BODY
                        2 -> EmojiCategory.ANIMALS_NATURE
                        3 -> EmojiCategory.FOOD_DRINK
                        4 -> EmojiCategory.TRAVEL_PLACES
                        5 -> EmojiCategory.ACTIVITIES
                        6 -> EmojiCategory.OBJECTS
                        7 -> EmojiCategory.SYMBOLS
                        8 -> EmojiCategory.FLAGS
                        else -> EmojiCategory.SMILEYS_EMOTION
                    }
                )
            }

            override fun onTabReselected(tab: TabLayout.Tab?) {}
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
        })
        addView(tabLayout)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        florisboard?.addEventListener(this)
        themeManager.registerOnThemeUpdatedListener(this)
        mainScope.launch {
            layouts.await()
            buildLayout()
            setActiveCategory(EmojiCategory.SMILEYS_EMOTION)
            themeManager.requestThemeUpdate(this@EmojiKeyboardView)
        }
    }

    override fun onDetachedFromWindow() {
        themeManager.unregisterOnThemeUpdatedListener(this)
        florisboard?.removeEventListener(this)
        layoutAdapters.clear()
        uiLayouts.clear()
        super.onDetachedFromWindow()
    }

    /**
     * Requests the layout for each category and attaches the built layout to [emojiViewFlipper].
     * This method runs in the [Dispatchers.Default] context and will block the main thread only
     * when it attaches a built category layout to the view hierarchy.
     */
    private suspend fun buildLayout() = withContext(Dispatchers.Default) {
        val recycledViewPool = RecyclerView.RecycledViewPool()
        recycledViewPool.setMaxRecycledViews(0, 64)
        for (category in EmojiCategory.values()) {
            val recyclerView = buildLayoutForCategory(category, recycledViewPool)
            uiLayouts[category] = recyclerView
            withContext(Dispatchers.Main) {
                emojiViewFlipper.addView(recyclerView)
            }
        }
    }

    /**
     * Builds the layout for a given [category]. This function runs in the [Dispatchers.Default]
     * context and will not block the main UI thread.
     *
     * @param category The category for which a layout should be built.
     * @return The layout (top-most view is a [ScrollView]).
     */
    @SuppressLint("ClickableViewAccessibility")
    private suspend fun buildLayoutForCategory(
        category: EmojiCategory,
        recycledViewPool: RecyclerView.RecycledViewPool
    ): RecyclerView = withContext(Dispatchers.Default) {
        val recyclerView = RecyclerView(context)
        val layoutManager = FlexboxLayoutManager(context)
        recyclerView.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        layoutManager.justifyContent = JustifyContent.SPACE_BETWEEN
        layoutManager.flexWrap = FlexWrap.WRAP
        recyclerView.layoutManager = layoutManager
        recyclerView.setRecycledViewPool(recycledViewPool)
        val adapter = EmojiKeyAdapter(
            layouts.await()[category].orEmpty().map { data -> EmojiKey(data).also { it.dummyCompute() } },
            this@EmojiKeyboardView
        )
        layoutAdapters[category] = adapter
        recyclerView.adapter = adapter

        recyclerView.setOnTouchListener { _, _ ->
            return@setOnTouchListener isScrollBlocked
        }
        return@withContext recyclerView
    }

    /**
     * Sets the [activeCategory] and changes the active view of [emojiViewFlipper] accordingly.
     *
     * @param newActiveCategory The new active category.
     */
    fun setActiveCategory(newActiveCategory: EmojiCategory) {
        // setting adapter forces recyclerview to return its views, so it can be used for new category
        uiLayouts[activeCategory]?.adapter = layoutAdapters[activeCategory]
        emojiViewFlipper.displayedChild =
            emojiViewFlipper.indexOfChild(uiLayouts[newActiveCategory])
        activeCategory = newActiveCategory
    }

    /**
     * Resets the [isScrollBlocked] flag whenever a [MotionEvent.ACTION_DOWN] occurs. This method
     * never intercepts any events and thus always returns false.
     *
     * @param ev The [MotionEvent] of the current touch event.
     * @return If this view wants to intercept the touch event. Always returns false here.
     */
    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean {
        if (ev?.actionMasked == MotionEvent.ACTION_DOWN) {
            isScrollBlocked = false
        }
        return false
    }

    /**
     * Invalidates the passed [keyView], sends a [MotionEvent.ACTION_CANCEL] to indicate the loss
     * of focus and prevents the HorizontalScrollView to scroll within this MotionEvent.
     */
    fun dismissKeyView(keyView: EmojiKeyView) {
        keyView.onTouchEvent(
            MotionEvent.obtain(
                0, 0, MotionEvent.ACTION_CANCEL, 0.0f, 0.0f, 0
            )
        )
        isScrollBlocked = true
    }

    override fun onThemeUpdated(theme: Theme) {
        val fgColor = theme.getAttr(Theme.Attr.MEDIA_FOREGROUND).toSolidColor().color
        val colorAccent = theme.getAttr(Theme.Attr.WINDOW_COLOR_ACCENT).toSolidColor().color
        tabLayout.tabIconTint = ColorStateList.valueOf(fgColor)
        tabLayout.setSelectedTabIndicatorColor(colorAccent)
    }
}
