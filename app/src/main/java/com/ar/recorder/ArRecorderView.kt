/*
 * Copyright 2021 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ar.recorder

import android.opengl.GLSurfaceView
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.ar.recorder.common.helpers.SnackbarHelper

/** Contains UI elements for AR Recorder. */
class ArRecorderView(val activity: ArRecorderActivity) : DefaultLifecycleObserver {
  val root = View.inflate(activity, R.layout.activity_main, null)
  val surfaceView = root.findViewById<GLSurfaceView>(R.id.surfaceview)
  val startButton = root.findViewById<Button>(R.id.button_start)
  val stopButton = root.findViewById<Button>(R.id.button_stop)
  val statusText = root.findViewById<TextView>(R.id.status_text)
  val editX = root.findViewById<EditText>(R.id.edit_x)
  val editY = root.findViewById<EditText>(R.id.edit_y)
  val editZ = root.findViewById<EditText>(R.id.edit_z)
  val createCircleButton = root.findViewById<Button>(R.id.button_create_circle)

  val session
    get() = activity.arCoreSessionHelper.session

  val snackbarHelper = SnackbarHelper()

  init {
    startButton.setOnClickListener {
      activity.renderer.startRecording()
      startButton.isEnabled = false
      stopButton.isEnabled = true
      statusText.text = activity.getString(R.string.status_recording)
    }

    stopButton.setOnClickListener {
      activity.renderer.stopRecording()
      startButton.isEnabled = true
      stopButton.isEnabled = false
      statusText.text = activity.getString(R.string.status_saved)
    }

    createCircleButton.setOnClickListener {
      try {
        val x = editX.text.toString().toFloat()
        val y = editY.text.toString().toFloat()
        val z = editZ.text.toString().toFloat()
        activity.renderer.createCircleAt(x, y, z)
      } catch (e: NumberFormatException) {
        snackbarHelper.showError(activity, "올바른 숫자를 입력하세요")
      }
    }
  }

  override fun onResume(owner: LifecycleOwner) {
    surfaceView.onResume()
  }

  override fun onPause(owner: LifecycleOwner) {
    surfaceView.onPause()
  }
}

