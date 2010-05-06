/*
Copyright (c) 2009 nullwire aps

Permission is hereby granted, free of charge, to any person
obtaining a copy of this software and associated documentation
files (the "Software"), to deal in the Software without
restriction, including without limitation the rights to use,
copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the
Software is furnished to do so, subject to the following
conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
OTHER DEALINGS IN THE SOFTWARE.

Contributors:
Mads Kristiansen, mads.kristiansen@nullwire.com
Glen Humphrey
Evan Charlton
Peter Hewitt
 */

package com.nullwire.trace;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FilenameFilter;
import java.io.IOException;
import java.lang.Thread.UncaughtExceptionHandler;
import java.util.ArrayList;
import java.util.List;

import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.apache.http.params.HttpProtocolParams;
import org.apache.http.protocol.HTTP;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.util.Log;

/**
 * Usage:
 *
 * 	    ExceptionHandler.setUrl('http://myserver.com/bugs')
 *      ExceptionHandler.setup(new ExceptionHandler.Processor() {
 *          boolean beginSubmit() {
 *          	showDialog(DIALOG_SUBMITTING_CRASH);
 *          	return true;
 *          }
 *
 *          void submitDone() {
 *          	cancelDialog(DIALOG_SUBMITTING_CRASH);
 *          	return true;
 *          }
 *
 *          void handlerInstalled() {}
 *      });
 */
public class ExceptionHandler {

	// Stores loaded stack traces in memory. Each element is
	// a tuple of (app version, android version, phone model, actual trace).
	private static ArrayList<String[]> sStackTraces = null;

	private static ActivityAsyncTask<Processor, Object, Object, Object> sTask;
	private static boolean sVerbose = false;
	private static int sMinDelay = 0;
	private static Integer sTimeout = null;
	private static boolean sSetupCalled = false;

	public static interface Processor {
		boolean beginSubmit();
		void submitDone();
		void handlerInstalled();
	}

	/**
	 * Setup the handler for unhandled exceptions, and submit stack
	 * traces from a previous crash.
	 *
	 * @param context
	 * @param processor
	 */
	public static boolean setup(Context context, final Processor processor) {
		// Make sure this is only called once.
		if (sSetupCalled) {
			// Tell the task that it now has a new context.
			if (sTask != null && !sTask.postProcessingDone()) {
				// We don't want to force the user to call our
				// notifyContextGone() if he doesn't care about that
				// functionality anyway, so in order to avoid the
				// InvalidStateException, ensure first that we are
				// disconnected.
				sTask.connectTo(null);
				sTask.connectTo(processor);
			}
			return false;
		}
		sSetupCalled = true;

		Log.i(G.TAG, "Registering default exceptions handler");
		
		// Files dir for storing the stack traces
		G.FILES_PATH = context.getFilesDir().getAbsolutePath();
		
		// Device model
		G.PHONE_MODEL = android.os.Build.MODEL;
		// Android version
		G.ANDROID_VERSION = android.os.Build.VERSION.RELEASE;
		
		// Get information about the Package
		PackageManager pm = context.getPackageManager();
		try {
			PackageInfo pi;
			// Version
			pi = pm.getPackageInfo(context.getPackageName(), 0);
			G.APP_VERSION = pi.versionName;
			// Package name
			G.APP_PACKAGE = pi.packageName;
			
		} catch (NameNotFoundException e) {
			Log.e(G.TAG, "Error collecting trace information", e);
		}

		if (sVerbose) {
			Log.i(G.TAG, "TRACE_VERSION: " + G.TraceVersion);
			Log.d(G.TAG, "APP_VERSION: " + G.APP_VERSION);
			Log.d(G.TAG, "APP_PACKAGE: " + G.APP_PACKAGE);
			Log.d(G.TAG, "FILES_PATH: " + G.FILES_PATH);
			Log.d(G.TAG, "URL: " + G.URL);
		}

		// First, search for and load stack traces
		getStackTraces();

		// Second, install the exception handler
		installHandler();
		processor.handlerInstalled();

		// Third, submit any traces we may have found
		return submit(processor);
	}

	/**
	 * Setup the handler for unhandled exceptions, and submit stack
	 * traces from a previous crash.
	 *
	 * Simplified version that uses a default processor.
	 *
	 * @param context
	 */
	public static boolean setup(Context context) {
		return setup(context, new Processor() {
			public boolean beginSubmit() { return true; }
			public void submitDone() {}
			public void handlerInstalled() {}
		});
	}

	/**
	 * If your "Processor" depends on a specific context/activity, call
	 * this method at the appropriate time, for example in your activity
	 * "onDestroy". This will ensure that we'll hold off executing
	 * "submitDone" or "handlerInstalled" until setup() is called again
	 * with a new context.
	 *
	 * @param context
	 */
	public static void notifyContextGone() {
		if (sTask == null)
			return;

		sTask.connectTo(null);
	}

	/**
	 * Submit stack traces. This is public because in some cases you
	 * might want to manually ask the traces to be submitted, for
	 * example after asking the user's permission.
	 */
	public static boolean submit(final Processor processor) {
		if (!sSetupCalled)
			throw new RuntimeException("you need to call setup() first");

		boolean stackTracesFound = hasStrackTraces();

		// If traces exist, we need to submit them
		if (stackTracesFound) {
			boolean proceed = processor.beginSubmit();
			if (proceed)
			{
				// Move the list of traces to a private variable.
				// This ensures that subsequent calls to hasStackTraces()
				// while the submission thread is ongoing, will return
				// false, or at least would refer to some new set of
				// traces.
				//
				// Yes, it would not be a problem from our side to have
				// two of these submission threads ongoing at the same
				// time (although it wouldn't currently happen as no new
				// traces can be added to the list besides through crashing
				// the process); however, the user's callback processor
				// might not be written to deal with that scenario.
				final ArrayList<String[]> tracesNowSubmitting = sStackTraces;
				sStackTraces = null;

				sTask = new ActivityAsyncTask<Processor, Object, Object, Object>(processor) {

					private long mTimeStarted;

					@Override
					protected void onPreExecute() {
						super.onPreExecute();
						mTimeStarted = System.currentTimeMillis();
					}

					@Override
					protected Object doInBackground(Object... params) {
						submitStackTraces(tracesNowSubmitting);

						long rest = sMinDelay - (System.currentTimeMillis() - mTimeStarted);
						if (rest > 0)
							try {
								Thread.sleep(rest);
							} catch (InterruptedException e) { e.printStackTrace(); }

						return null;
					}

					@Override
					protected void onCancelled() {
						super.onCancelled();
					}

					@Override
					protected void processPostExecute(Object result) {
						mWrapped.submitDone();
					}
				};
				sTask.execute();
			}
		}

		return stackTracesFound;
	}

	/**
	 * Version of submit() that doesn't take a processor.
	 */
	public static boolean submit() {
		return submit(new Processor() {
			public boolean beginSubmit() { return true; }
			public void submitDone() {}
			public void handlerInstalled() {}
		});
	}

	/**
	 * Set a custom URL to be used when submitting stracktraces.
	 *
	 * @param url
	 */
	public static void setUrl(String url) {
		G.URL = url;
	}

	/**
	 * Set a custom tag used for log messages outputted by this lib.
	 *
	 * @param tag
	 */
	public static void setTag(String tag) {
		G.TAG = tag;
	}

	/**
	 * Tell us to be more verbose with respect to the log messages we
	 * output.
	 *
	 * @param verbose
	 */
	public static void setVerbose(boolean verbose) {
		sVerbose = verbose;
	}

	/**
	 * When you are showing for example a dialog during submission,
	 * there will be situations in which submission is done very
	 * quickly the the dialog is not more than a flicker on the screen.
	 *
	 * This allows you to configure a minimum time that needs to pass
	 * (in milliseconds) before the submitDone() callback is called.
	 *
	 * @param delay
	 */
	public static void setMinDelay(int delay) {
		sMinDelay = delay;
	}

	/**
	 * Configure a timeout to use when submitting stack traces.
	 *
	 * If not set the default timeout will be used.
	 *
	 * @param timeout
	 */
	public static void setHttpTimeout(Integer timeout) {
		sTimeout = timeout;
	}

	/**
	 * Return true if there are stacktraces that need to be submitted.
	 *
	 * Useful for example if you would like to ask the user's permission
	 * before submitting. You can then use Processor.beginSubmit() to
	 * stop the submission from occurring.
	 */
	public static boolean hasStrackTraces() {
		return (getStackTraces().size() > 0);
	}

	/**
	 * Delete loaded stack traces from memory. Normally, this will
	 * happen automatically after submission, but if you don't submit,
	 * this is for you.
	 */
	public static void clear() {
		sStackTraces = null;
	}

	/**
	 * Search for stack trace files, read them into memory and delete
	 * them from disk.
	 *
	 * They are read into memory immediately so we can go ahead and
	 * install the exception handler right away, and only then try
	 * and submit the traces.
	 */
	private static ArrayList<String[]> getStackTraces() {
		if (sStackTraces != null) {
			return sStackTraces;
		}

		Log.d(G.TAG, "Looking for exceptions in: " + G.FILES_PATH);

		// Find list of .stacktrace files
		File dir = new File(G.FILES_PATH + "/");
		// Try to create the files folder if it doesn't exist
		if (!dir.exists())
			dir.mkdir();
		FilenameFilter filter = new FilenameFilter() {
			public boolean accept(File dir, String name) {
				return name.endsWith(".stacktrace");
			}
		};
		String[] list = dir.list(filter);

		Log.d(G.TAG, "Found "+list.length+" stacktrace(s)");

		try {
			final int MAX_TRACES = 5;
			sStackTraces = new ArrayList<String[]>();
			for (int i=0; i < list.length; i++)
			{
				// Limit to a certain number of SUCCESSFULLY read traces
				if (sStackTraces.size() >= MAX_TRACES)
					break;

				String filePath = G.FILES_PATH + "/" + list[i];

				try {
					String androidVersion = null;
					String phoneModel = null;

					// Extract the version from the filename: "packagename-version-...."
					String version = list[i].split("-")[0];
					Log.d(G.TAG, "Stacktrace in file '"+filePath+"' belongs to version " + version);
					// Read contents of stacktrace
					StringBuilder contents = new StringBuilder();
					BufferedReader input =  new BufferedReader(new FileReader(filePath));
					try {
						String line = null;
						while ((line = input.readLine()) != null) {
							if (androidVersion == null) {
								androidVersion = line;
								continue;
							}
							else if (phoneModel == null) {
								phoneModel = line;
								continue;
							}
							contents.append(line);
							contents.append(System.getProperty("line.separator"));
						}
					} finally {
						input.close();
					}
					String stacktrace = contents.toString();

					sStackTraces.add(new String[] {
						version, androidVersion, phoneModel, stacktrace });
				} catch (FileNotFoundException e) {
					Log.e(G.TAG, "Failed to load stack trace", e);
				} catch (IOException e) {
					Log.e(G.TAG, "Failed to load stack trace", e);
				}
			}

			return sStackTraces;
		}
		finally {
			// Delete ALL the stack traces, even those not read (if
			// there were too many), and do this within a finally
			// clause so that even if something very unexpected went
			// wrong above, it hopefully won't happen again the next
			// time around (because the offending files are gone).
			for (int i=0; i < list.length; i++)
			{
				try {
					File file = new File(G.FILES_PATH+"/"+list[i]);
					file.delete();
				} catch (Exception e) {
					Log.e(G.TAG, "Error deleting trace file: "+list[i], e);
				}
			}
		}
	}

	/**
	 * Look into the files folder to see if there are any "*.stacktrace" files.
	 * If any are present, submit them to the trace server.
	 */
	private static void submitStackTraces(ArrayList<String[]> list) {
		try {
			if (list == null)
				return;
			for (int i=0; i < list.size(); i++)
			{
				String[] record = list.get(i);
				String version = record[0];
				String androidVersion = record[1];
				String phoneModel = record[2];
				String stacktrace = record[3];

				Log.d(G.TAG, "Transmitting stack trace: " + stacktrace);
				// Transmit stack trace with POST request
				DefaultHttpClient  httpClient = new DefaultHttpClient();
				HttpParams params = httpClient.getParams();
				// Lighty 1.4 has trouble with the expect header
				// (http://redmine.lighttpd.net/issues/1017), and a
				// potential workaround is only included in 1.4.21
				// (http://www.lighttpd.net/2009/2/16/1-4-21-yes-we-can-do-another-release).
				HttpProtocolParams.setUseExpectContinue(params, false);
				if (sTimeout != null) {
					HttpConnectionParams.setConnectionTimeout(params, sTimeout);
					HttpConnectionParams.setSoTimeout(params, sTimeout);
				}
				HttpPost httpPost = new HttpPost(G.URL);
				List <NameValuePair> nvps = new ArrayList <NameValuePair>();
				nvps.add(new BasicNameValuePair("package_name", G.APP_PACKAGE));
				nvps.add(new BasicNameValuePair("package_version", version));
				nvps.add(new BasicNameValuePair("phone_model", phoneModel));
				nvps.add(new BasicNameValuePair("android_version", androidVersion));
				nvps.add(new BasicNameValuePair("stacktrace", stacktrace));
				httpPost.setEntity(new UrlEncodedFormEntity(nvps, HTTP.UTF_8));
				// We don't care about the response, so we just hope it
				// went well and on with it.
				httpClient.execute(httpPost);
			}
		} catch (Exception e) {
			Log.e(G.TAG, "Error submitting trace", e);
		}
	}

	private static void installHandler() {
		UncaughtExceptionHandler currentHandler = Thread.getDefaultUncaughtExceptionHandler();
		if (currentHandler != null && sVerbose)
			Log.d(G.TAG, "current handler class="+currentHandler.getClass().getName());
		// don't register again if already registered
		if (!(currentHandler instanceof DefaultExceptionHandler)) {
			// Register default exceptions handler
			Thread.setDefaultUncaughtExceptionHandler(
					new DefaultExceptionHandler(currentHandler));
		}
	}
}
