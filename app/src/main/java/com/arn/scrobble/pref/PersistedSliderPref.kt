package com.arn.scrobble.pref

import android.content.Context
import android.content.res.TypedArray
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import androidx.preference.R
import com.arn.scrobble.Stuff
import com.google.android.material.slider.Slider
import java.text.NumberFormat


/**
 * Created by arn on 09/09/2017.
 */

class PersistedSliderPref(context: Context, attrs: AttributeSet?, defAttrs: Int, defStyle: Int) :
    Preference(context, attrs, defAttrs, defStyle), Slider.OnSliderTouchListener {
    constructor(context: Context, attrs: AttributeSet?, defAttrs: Int) : this(
        context,
        attrs,
        defAttrs,
        0
    )

    constructor(context: Context, attrs: AttributeSet?) : this(
        context,
        attrs,
        R.attr.seekBarPreferenceStyle
    )

    constructor(context: Context) : this(context, null)

    private val mMin: Int
    private val mMax: Int
    private val mSeekBarIncrement: Int
    private var value: Int

    init {
        layoutResource = com.arn.scrobble.R.layout.pref_slider
        val a = context.obtainStyledAttributes(
            attrs, R.styleable.SeekBarPreference, defAttrs, defStyle
        )

        mMin = a.getInt(R.styleable.SeekBarPreference_min, 0)
        mMax = a.getInt(R.styleable.SeekBarPreference_android_max, 100)
        mSeekBarIncrement = a.getInt(R.styleable.SeekBarPreference_seekBarIncrement, 0)
        value = mMin
        a.recycle()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.isDividerAllowedAbove = false
        val slider = holder.itemView.findViewById<Slider>(R.id.seekbar)
        slider.valueFrom = mMin.toFloat()
        slider.valueTo = mMax.toFloat()
        if (mSeekBarIncrement > 0)
            slider.stepSize = mSeekBarIncrement.toFloat()

        setValue(slider)

        slider.clearOnSliderTouchListeners()
        slider.addOnSliderTouchListener(this)

        slider.setLabelFormatter(::getFormattedValue)
        slider.isEnabled = isEnabled
    }

    override fun onGetDefaultValue(a: TypedArray, index: Int): Any? {
        return a.getInt(index, mMin)
    }

    private fun setValue(slider: Slider) {
        slider.value = value.toFloat()
        ((slider.parent as ViewGroup).getChildAt(1) as TextView).apply {
            text = getFormattedValue(slider.value)
            visibility = View.VISIBLE
        }
        persistInt(value)
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        // conform value to avoid a crash
        var tmpValue = getPersistedInt((defaultValue as? Int) ?: mMin)
        if (tmpValue > mMax)
            tmpValue = mMax
        if (tmpValue < mMin)
            tmpValue = mMin

        if (mSeekBarIncrement > 0) {
            tmpValue = (tmpValue / mSeekBarIncrement) * mSeekBarIncrement
        }

        value = tmpValue
    }

    override fun onStartTrackingTouch(slider: Slider) {
        ((slider.parent as ViewGroup).getChildAt(1) as TextView).visibility = View.INVISIBLE
    }

    override fun onStopTrackingTouch(slider: Slider) {
        this.value = slider.value.toInt()
        setValue(slider)
    }

    private fun getFormattedValue(floatValue: Float): String {
        val value = floatValue.toInt()
        val suffix = key?.split("_")?.getOrNull(1)
        return when (suffix) {
            "per",
            "percent" ->
                "$value%"
            "secs",
            "time" ->
                Stuff.humanReadableDuration(value)
            else ->
                NumberFormat.getInstance().format(value)
        }
    }

    // No need to save instance state if it is persistent

}