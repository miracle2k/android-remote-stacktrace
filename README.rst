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

This is straightforward: The setup() call install the custom exception
handler, and submit any existing traces from earlier crashes.

Custom processor
----------------

If you would like to customize the process, for example letting the user
know about the stack trace submission, you can use a processor:

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        ExceptionHandler.setup(this, new ExceptionHandler.Processor() {
            @Override
            public boolean beginSubmit() {
                mExceptionSubmitDialog = AlertDialog.Builder().create();
                return true;
            }

            @Override
            public void submitDone() {
                mExceptionSubmitDialog.cancel();
            }

            @Override
            public void handlerInstalled() {}
        }));

        buildUserInterface();
    }


You probably want to make the dialog have no buttons and set "cancelable"
to false.


Asking the user
---------------

You may want to ask the user if he agrees with submitting the trace.
This is really easy as well, if somewhat awkward to write:

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        ExceptionHandler.setup(this, new ExceptionHandler.Processor() {
            @Override
            public boolean beginSubmit() {
                // Don't submit traces that may exist, we just
                // install the handler.
                return false;
            }
            @Override
            public void submitDone() {}
            @Override
            public void handlerInstalled() {}
        }));

        // Manually have a look at whether there are traces, and if so,
        // ask the user if we may submit them.
        if (ExceptionHandler.hasStrackTraces())
            askUserIfWeMaySubmit();
    }

    private void askUserPermissionResult(boolean permissionGranted) {
        if (!permissionGranted) {
            // Clear the traces we won't submit now from memory.
            ExceptionHandler.clear();
        }
        else {
            ExceptionHandler.submit();
        }
    }


Handling orientation change
---------------------------

What happens if the user changes the orientation of the device while the
thread sending out the stack traces is still active?

Well, the exception handler ensures that no second thread will be started,
and you can rely on the handlerInstalled() callback to be run for every setup()
call, just as if the handler was installed for the first time.

However, notice a couple of things:

 * Our efforts of deferring as much code as possible until after the handler
   is installed by using the handlerInstalled() callback are mostly bypassed.
   The second instance of the activity will have handlerInstalled() executed
   right away, while the submission thread from the first instance is still
   waiting to complete; only then the exception hook will be registered.

 * Note that the dialog we display in the previous example is manually created;
   The Activity's showDialog() is not used. This is because showDialog() would
   automatically recreate the dialog upon orientation change, while the
   submitDone() callback from the first setup() call still references the first
   dialog object, from the first Activity instance.

Those are simple to solve. The class already does some work to ensure that
when setup() is called a second time before the submission thread has finished,
that subsequently the new processor object from the current setup() call will
be used by the thread. This already ensures that in many cases, the correct
dialog instance would be referenced if you were to use showDialog().

There is however an edge case: It is entirely possible that the submission
thread finishes **before** the new is created, but after the state of the
previous activity has been saved (including the active dialogs). To prevent
this from happening, you need to call notifyContextGone() in Activity.onDestroy():

    @Override
    protected void onDestroy() {
        ExceptionHandler.notifyContextGone();
        super.onDestroy();
    }

This will ensure that ExceptionHandler holds off executing the submitDone()
callback until the next time setup() is called.


Customizations
--------------

The following methods need to be run before the ExceptionHandler.setup()
call, for example:

    ExceptionHandler.setUrl('http://my.site.com/bugs');
    ExceptionHander.setup(this);

The following options are currently available:

setUrl() allows you customize the url traces are submitted to.

setTag() allows you to customize the log tag used by the library.

setVerbose() tells the library to be a bit more verbose in terms of the
log messages that are outputted.

setMinDelay() allows you to specify a minimum time that needs to pass
before the submitDone() callback is executed. Useful if you don't want
UI elements that you have specifically shown to indicate trace submission
to flicker-like disappear again.

setHttpTimeout() to change the default timeout for the HTTP submission.


Building
========

Copy "local.properties.template" to "local.properties", and edit it to
set the correct "lib.dir" path to your Android SDK platform. Then run:

    $ ant package
