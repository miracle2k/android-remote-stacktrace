package com.nullwire.trace.example;

import com.nullwire.trace.ExceptionHandler;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;

public class MainActivity extends Activity {

	private static final int DIALOG_SUBMITTING_EXCEPTIONS = 1;

	private Dialog mExceptionSubmitDialog;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        ExceptionHandler.setMinDelay(4000);
        ExceptionHandler.setup(this, new ExceptionHandler.Processor() {
        	@Override
			public boolean beginSubmit() {
				showDialog(DIALOG_SUBMITTING_EXCEPTIONS);
				return true;
			}

			@Override
			public void submitDone() {
				mExceptionSubmitDialog.cancel();
			}

			@Override
			public void handlerInstalled() {
				continueActivitySetup();
			}
		});
    }

    private void continueActivitySetup() {
    	findViewById(R.id.crash_button).setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				throw new RuntimeException("exception requested by user");
			}
		});
    }

	@Override
	protected Dialog onCreateDialog(int id) {
		if (id == DIALOG_SUBMITTING_EXCEPTIONS) {
			mExceptionSubmitDialog = new AlertDialog.Builder(this)
				.setTitle("Please wait...")
				.setMessage("The application has previously crashed "+
						"unexpectedly. Please wait while I am submitting "+
						"some information that will help us fix the problem.")
				.setCancelable(false).create();
			return mExceptionSubmitDialog;
		}
		else
			return super.onCreateDialog(id);
	}
}