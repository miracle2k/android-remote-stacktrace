android-remote-stracktrace
==========================

Simple
------


    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        ExceptionHandler.setup(this);

        buildUserInterface();
    }

This is straightforward: The setup() call will submit any existing
stacktraces, and will then install the custom exception handler.
As you can see, there is an obvious downside: Submitting the traces
happens asychronously in a thread, so while submission is ongoing,
your main thread, in this case the buildUserInterface() call is 
proceeding. Is is therefore possible that exceptions occur in that 
time which will not be processed, since the handler has not yet 
been installed.


Custom processor
----------------

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

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
                buildUserInterface();
            }
        }));
    }

As you can see, we are avoiding the problem described in the previous
section by calling buildUserInterface() inside the "handlerInstalled"
callback. This means we can be sure that any exceptions occuring there
will be caught, and a trace be saved.

Further, to ensure that in case trace submission takes a bit longer 
(maybe the device is on a bad mobile connection), the user doesn't just
see a blank screen, we are showing a dialog. You probably want to make
this dialog have no buttons and set "cancelable" to false.


Customizations
--------------

Those methods need to be run before the ExceptionHandler.setup() call.

setUrl() allows you customize the url traces are submitted to.

setTag() allows you to customize the log tag used by the library.

setVerbose() tells the library to be a bit more verbose in terms of the
log messages that are outputted.

setMinDelay() allows you to specify a minimum time that needs to pass
before the submitDone() callback is executed. Useful if you don't want
UI elements that you have specifically shown to indicate trace submission
to flicker-like disappear again.

setHttpTimeout() to change the default timeout for the HTTP submission.
