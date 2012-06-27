package com.yahdav.liron.pushupcounter;

import java.util.Formatter;
import java.util.Locale;

import android.app.Activity;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeech.OnInitListener;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.NumberPicker;
import android.widget.TextView;

public class PushupCounterActivity extends Activity implements
		SensorEventListener, OnClickListener {
	private enum Transition {
		UP, DOWN, STOPPED
	}

	private enum PushupState {
		MOVING_UP, MOVING_DOWN, STOPPED_BOTTOM, STOPPED_TOP
	}

	private static final int SENSOR_DELAY = SensorManager.SENSOR_DELAY_GAME;
	private static final float NOISE = (float) 2.0;
	private static final boolean DEBUG = false;

	private PushupState mPushupState = PushupState.STOPPED_TOP;
	private int mPushupCount = 0;

	private float mGravityX, mGravityY, mGravityZ;

	private SensorManager mSensorManager;
	private Sensor mLinearAccelerometer;
	private Sensor mGravity;

	private boolean isStarted = false;

	private TextToSpeech mTts;
	private WakeLock mWakeLock;

	private static float[] unitVector(float x, float y, float z) {
		float mag = (float) Math.sqrt(Math.pow(x, 2.0) + Math.pow(y, 2.0)
				+ Math.pow(z, 2.0));
		return new float[] { x / mag, y / mag, z / mag };
	}

	float dotProduct(float x, float y, float z, float otherX, float otherY,
			float otherZ) {
		return (x * otherX) + (y * otherY) + (z * otherZ);
	}

	float projection(float x, float y, float z, float ontoX, float ontoY,
			float ontoZ) {
		float[] ontoUnitVector = unitVector(ontoX, ontoY, ontoZ);
		return dotProduct(x, y, z, ontoUnitVector[0], ontoUnitVector[1],
				ontoUnitVector[2]);
	}

	/** Called when the activity is first created. */

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);
		mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
		mLinearAccelerometer = mSensorManager
				.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
		mGravity = mSensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);

		mTts = new TextToSpeech(this, new OnInitListener() {
			public void onInit(int status) {
				if (status == TextToSpeech.SUCCESS) {
					// Set preferred language to US English.
					// Note that a language may not be available, and the result
					// will indicate this.
					mTts.setLanguage(Locale.US);
				}
			}
		});

		Button btnStartStop = (Button) findViewById(R.id.btnStartStop);
		btnStartStop.setOnClickListener(this);

		Button btnReset = (Button) findViewById(R.id.btnReset);
		btnReset.setOnClickListener(this);

		NumberPicker np = (NumberPicker) findViewById(R.id.np);
		np.setMaxValue(100);
		np.setMinValue(0);
		np.setValue(20);

		if (!DEBUG) {
			findViewById(R.id.top_table).setVisibility(View.GONE);
			findViewById(R.id.etDebugInfo).setVisibility(View.GONE);
		}
	}

	protected void onResume() {
		super.onResume();
	}

	protected void onPause() {
		super.onPause();
		if (isStarted) {
			onClick(findViewById(R.id.btnStartStop));
		}
	}

	public void onClick(View v) {
		if (v == findViewById(R.id.btnReset)) {
			onBtnResetClick(v);
		} else if (v == findViewById(R.id.btnStartStop)) {
			Button btnStartStop = (Button) v;

			if (isStarted) { // stopping
				isStarted = false;
				btnStartStop.setText("Start");
				onBtnStop();
			} else { // starting
				isStarted = true;
				btnStartStop.setText("Stop");
				onBtnStart();
			}
		}
	}

	private void onBtnStart() {
		mSensorManager.registerListener(this, mLinearAccelerometer,
				SENSOR_DELAY);
		mSensorManager.registerListener(this, mGravity, SENSOR_DELAY);

		PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
		mWakeLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK,
				"Pushup Counter");
		mWakeLock.acquire();
	}

	private void onBtnStop() {
		onBtnResetClick(findViewById(R.id.btnReset));
		mWakeLock.release();
		mSensorManager.unregisterListener(this);
	}

	public void onBtnResetClick(View v) {
		EditText etDebugInfo = (EditText) findViewById(R.id.etDebugInfo);
		etDebugInfo.setText("");
		TextView tvPushupCount = (TextView) findViewById(R.id.txtPushupCount);
		tvPushupCount.setText("0");
		mPushupCount = 0;
	}

	public void onAccuracyChanged(Sensor sensor, int accuracy) {
		// can be safely ignored for this app
	}

	public void onSensorChanged(SensorEvent event) {
		if (event.sensor == mLinearAccelerometer) {
			onLinearAccelerometerChanged(event);
		} else if (event.sensor == mGravity) {
			onGravityChanged(event);
		}
	}

	private void onGravityChanged(SensorEvent event) {
		mGravityX = event.values[0];
		mGravityY = event.values[1];
		mGravityZ = event.values[2];
	}

	private void onLinearAccelerometerChanged(SensorEvent event) {
		float x = event.values[0];
		float y = event.values[1];
		float z = event.values[2];

		// positive = moving down -> stop
		// negative = moving up -> stop
		float proj = projection(x, y, z, mGravityX, mGravityY, mGravityZ);
		proj = Math.abs(proj) - NOISE > 0.0f ? proj : 0.0f;

		if (Math.abs(0.0f - proj) > 0.01) {
			Formatter f = new Formatter();
			debugLog(f.format("Projection: %5.2f", proj).toString());

			if (proj > 0.0f) {
				transition(Transition.DOWN);
			} else {
				transition(Transition.UP);
			}
		} else {
			transition(Transition.STOPPED);
		}
	}

	private void transition(Transition transition) {
		PushupState prevState = mPushupState;
		PushupState newState = getNewPushupState(transition, prevState);
		if (newState != null) {
			handleNewState(prevState, newState);
		}
	}

	private void handleNewState(PushupState prevState, PushupState newState) {
		mPushupState = newState;

		debugLog(mPushupState.toString());

		if (prevState == PushupState.MOVING_UP
				&& newState == PushupState.STOPPED_TOP) {
			incrementPushupCount();
		}
	}

	private void debugLog(String text) {
		if (DEBUG) {
			EditText etDebugInfo = (EditText) findViewById(R.id.etDebugInfo);
			etDebugInfo.setText(text + "\n" + etDebugInfo.getText());
		}
	}

	private void incrementPushupCount() {
		mPushupCount++;
		if (((CheckBox) findViewById(R.id.count_out_load)).isChecked()) {
			mTts.speak(Integer.toString(mPushupCount),
					TextToSpeech.QUEUE_FLUSH, null);
		}

		TextView tvPushupCount = (TextView) findViewById(R.id.txtPushupCount);
		tvPushupCount.setText(Integer.toString(mPushupCount));

		NumberPicker np = (NumberPicker) findViewById(R.id.np);
		if (mPushupCount >= np.getValue()) {
			if (isStarted) {
				onClick(findViewById(R.id.btnStartStop));
			}
		}
	}

	private PushupState getNewPushupState(Transition transition,
			PushupState prevState) {
		PushupState newState = null;
		switch (prevState) {
		case MOVING_DOWN:
			if (transition == Transition.STOPPED) {
				newState = PushupState.STOPPED_BOTTOM;
			}
			break;
		case STOPPED_TOP:
			if (transition == Transition.DOWN) {
				newState = PushupState.MOVING_DOWN;
			}
			break;
		case STOPPED_BOTTOM:
			if (transition == Transition.UP) {
				newState = PushupState.MOVING_UP;
			}
			break;
		case MOVING_UP:
			if (transition == Transition.STOPPED) {
				newState = PushupState.STOPPED_TOP;
			}
			break;
		}
		return newState;
	}
}