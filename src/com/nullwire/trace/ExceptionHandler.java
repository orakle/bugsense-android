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
import java.io.FileReader;
import java.io.FilenameFilter;
import java.lang.Thread.UncaughtExceptionHandler;
import java.util.ArrayList;
import java.util.List;

import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
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
 *          void handlerInstalled() {
 *          	continueWithAppSetup();
 *          }
 *      });
 */
public class ExceptionHandler {

	private static String[] stackTraceFileList = null;

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
	 * Submit any saved stracktraces, then setup the handler for
	 * unhandled exceptions.
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
			else {
				// We want to provide an API where we guarantee that the
				// handlerInstalled callback will be called, for the user
				// to continue processing.
				processor.handlerInstalled();
			}
			return false;
		}
		sSetupCalled = true;

		Log.i(G.TAG, "Registering default exceptions handler");
		// Get information about the Package
		PackageManager pm = context.getPackageManager();
		try {
			PackageInfo pi;
			// Version
			pi = pm.getPackageInfo(context.getPackageName(), 0);
			G.APP_VERSION = pi.versionName;
			// Package name
			G.APP_PACKAGE = pi.packageName;
			// Files dir for storing the stack traces
			G.FILES_PATH = context.getFilesDir().getAbsolutePath();
			// Device model
			G.PHONE_MODEL = android.os.Build.MODEL;
			// Android version
			G.ANDROID_VERSION = android.os.Build.VERSION.RELEASE;
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

		boolean stackTracesFound = (searchForStackTraces().length > 0);

		// If no traces exist, we don't need to submit anything, and we
		// can go straight to setting up the exception intercept.
		if (!stackTracesFound) {
			installHandler();
			processor.handlerInstalled();
		}
		// Otherwise, we need to submit the existing stacktraces.
		else {
			boolean proceed = processor.beginSubmit();
			if (!proceed) {
				installHandler();
				processor.handlerInstalled();
			}
			else {
				sTask = new ActivityAsyncTask<Processor, Object, Object, Object>(processor) {

					private long mTimeStarted;

					@Override
					protected void onPreExecute() {
						super.onPreExecute();
						mTimeStarted = System.currentTimeMillis();
					}

					@Override
					protected Object doInBackground(Object... params) {
						submitStackTraces();

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
						installHandler();
						mWrapped.handlerInstalled();
					}

					@Override
					protected void processPostExecute(Object result) {
						mWrapped.submitDone();
						installHandler();
						mWrapped.handlerInstalled();
					}
				};
				sTask.execute();
			}
		}

		return stackTracesFound;
	}

	/**
	 * Submit any saved stacktraces, then setup the handler for
	 * unhandled exceptions.
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
		// Once setup has been called, always return false. The
		// stack traces that now exists are essentially in the process
		// of being submitted right now.
		// This means that you can safely use this method in your
		// Activity.onCreate() ->
		if (sSetupCalled)
			return false;
		return (searchForStackTraces().length > 0);
	}

	/**
	 * Search for stack trace files.
	 * @return
	 */
	private static String[] searchForStackTraces() {
		if ( stackTraceFileList != null ) {
			return stackTraceFileList;
		}
		File dir = new File(G.FILES_PATH + "/");
		// Try to create the files folder if it doesn't exist
		dir.mkdir();
		// Filter for ".stacktrace" files
		FilenameFilter filter = new FilenameFilter() {
			public boolean accept(File dir, String name) {
				return name.endsWith(".stacktrace");
			}
		};
		return (stackTraceFileList = dir.list(filter));
	}

	/**
	 * Look into the files folder to see if there are any "*.stacktrace" files.
	 * If any are present, submit them to the trace server.
	 */
	public static void submitStackTraces() {
		try {
			Log.d(G.TAG, "Looking for exceptions in: " + G.FILES_PATH);
			String[] list = searchForStackTraces();
			if ( list != null && list.length > 0 ) {
				Log.d(G.TAG, "Found "+list.length+" stacktrace(s)");
				for (int i=0; i < list.length; i++) {
					String filePath = G.FILES_PATH+"/"+list[i];
					// Extract the version from the filename: "packagename-version-...."
					String version = list[i].split("-")[0];
					Log.d(G.TAG, "Stacktrace in file '"+filePath+"' belongs to version " + version);
					// Read contents of stacktrace
					StringBuilder contents = new StringBuilder();
					BufferedReader input =  new BufferedReader(new FileReader(filePath));
					String line = null;
					String androidVersion = null;
					String phoneModel = null;
					while (( line = input.readLine()) != null){
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
					input.close();
					String stacktrace;
					stacktrace = contents.toString();
					Log.d(G.TAG, "Transmitting stack trace: " + stacktrace);
					// Transmit stack trace with POST request
					DefaultHttpClient httpClient = null;
					if (sTimeout != null) {
						HttpParams params = new BasicHttpParams();
						HttpConnectionParams.setConnectionTimeout(params, sTimeout);
						HttpConnectionParams.setSoTimeout(params, sTimeout);
						httpClient = new DefaultHttpClient(params);
					}
					else {
						// Simply use the default timeout
						httpClient = new DefaultHttpClient();
					}
					HttpPost httpPost = new HttpPost(G.URL);
					List <NameValuePair> nvps = new ArrayList <NameValuePair>();
					nvps.add(new BasicNameValuePair("package_name", G.APP_PACKAGE));
					nvps.add(new BasicNameValuePair("package_version", version));
					nvps.add(new BasicNameValuePair("phone_model", phoneModel));
					nvps.add(new BasicNameValuePair("android_version", androidVersion));
					nvps.add(new BasicNameValuePair("stacktrace", stacktrace));
					httpPost.setEntity(new UrlEncodedFormEntity(nvps, HTTP.UTF_8));
					// We don't care about the response, so we just hope it went well and on with it
					httpClient.execute(httpPost);
				}
			}
		} catch (Exception e) {
			Log.e(G.TAG, "Error submitting trace", e);
		} finally {
			try {
				String[] list = searchForStackTraces();
				for ( int i = 0; i < list.length; i ++ ) {
					File file = new File(G.FILES_PATH+"/"+list[i]);
					file.delete();
				}
			} catch (Exception e) {
				Log.e(G.TAG, "Error deleting trace files", e);
			}
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
