/*
 * Copyright (C) 2012 The Android Open Source Project
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
package com.android.server.display;

// Importing from individual classes based on requirements
import com.android.internal.util.DumpUtils;
import com.android.internal.util.IndentingPrintWriter;

import android.content.Context;
import android.database.ContentObserver;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Slog;
import android.view.Display;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceControl;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A display adapter that uses overlay windows to simulate secondary displays
 * for development purposes.  Use Development Settings to enable one or more
 * overlay displays.
 * <p>
 * This object has two different handlers (which may be the same) which must not
 * get confused.  The main handler is used to posting messages to the display manager
 * service as usual.  The UI handler is only used by the {@link OverlayDisplayWindow}.
 * </p><p>
 * Display adapters are guarded by the {@link DisplayManagerService.SyncRoot} lock.
 * </p><p>
 * This adapter is configured via the
 * {@link android.provider.Settings.Global#OVERLAY_DISPLAY_DEVICES} setting. This setting should be
 * formatted as follows:
 * <pre>
 * [mode1]|[mode2]|...,[flag1],[flag2],...
 * </pre>
 * with each mode specified as:
 * <pre>
 * [width]x[height]/[densityDpi]
 * </pre>
 * Supported flags:
 * <ul>
 * <li> <pre>secure</pre>: creates a secure display</li>
 * </ul>
 * </p>
 */

// Declaring the class "OverlayDisplayAdapter" which inherits from the parent class DisplayAdapter
final class OverlayDisplayAdapter extends DisplayAdapter {
    // TAG indicates the module from which the log is raised. In this case, it's the "OverlayDisplayAdapter"    
    static final String TAG = "OverlayDisplayAdapter";
    static final boolean DEBUG = false;

    // Range of Display Width and Height are set to be in between 100 to 4096 
    private static final int MIN_WIDTH = 100;
    private static final int MIN_HEIGHT = 100;
    private static final int MAX_WIDTH = 4096;
    private static final int MAX_HEIGHT = 4096;
   
    // Display pattern to match the reg. expression containing the modes and flags
    private static final Pattern DISPLAY_PATTERN =
            Pattern.compile("([^,]+)(,[a-z]+)*");
    // Mode pattern matches the regular expression denoted by "(width)*(height)/(densityDpi)" 
    // which calculates the mode of the display device. 
    private static final Pattern MODE_PATTERN =
            Pattern.compile("(\\d+)x(\\d+)/(\\d+)");

    // Indicate a Unique ID for the Overlay Display Adapter to be used in "DisplayDevice.java"
    private static final String UNIQUE_ID_PREFIX = "overlay:";
    
    /* 
        Class Variable "mUiHandler" of type Handler which is used for posting to the message queue and also for
        sending messages to the queue which can then run on an entirely different thread
    */
    private final Handler mUiHandler;
    
    // Creating Array list mOverlays of type OverlayDisplayHandle to include all the overlay display devices
     private final ArrayList<OverlayDisplayHandle> mOverlays =
            new ArrayList<OverlayDisplayHandle>();
    
    // Variable to store the content of the current Overlay Display Device from the Settings class
    private String mCurrentOverlaySetting = "";

    // Called with SyncRoot lock held.
    /* 
        Constructor taking arguments for both parent class and the sub class variables
        The parent class constructor is invoked by the super method that passes values to the parent class variables.
    */
    public OverlayDisplayAdapter(DisplayManagerService.SyncRoot syncRoot,
            Context context, Handler handler, Listener listener, Handler uiHandler) {
        super(syncRoot, context, handler, listener, TAG);
        mUiHandler = uiHandler;
    }
    
    /*
        Overriding the parent class method "dumpLocked"
        "dumpLocked" dumps the local state of the invoking class object
        Takes up parameter pw of type PrintWriter imported from java.io.PrintWriter
    */
    @Override
    public void dumpLocked(PrintWriter pw) {
        // Parent class method is invoked by passing the object pw of type PrintWriter
        super.dumpLocked(pw);

        // Prints to the console the value of the current overlay setting and the size of the Array List "mOverlays" in a text output stream
        pw.println("mCurrentOverlaySetting=" + mCurrentOverlaySetting);
        pw.println("mOverlays: size=" + mOverlays.size());
        
        // Iterating over the Array List, each element of "OverlayDisplayHandle" gets dumped locally
        for (OverlayDisplayHandle overlay : mOverlays) {
            overlay.dumpLocked(pw);
        }
    }
    
    /*
        Overriding the parent class method "registerLocked"
        "registerLocked" registers the Overlay Display Adapter with the Display Manager
        Default display devices gets registered during the boot process while the remaining devices gets registered dynamically.
    */

    @Override
    public void registerLocked() {
        // Invoking the parent class method "registerLocked"
        super.registerLocked();
        
        /* 
            Invoking a new thread which checks for any content change in the Display device in the message queue
            and call the method "updateOverlayDisplayDevices" to update the status in the Display Manager.
            Runnable implements a new thread which either schedules messages/ post to the message queue.
        */    
        getHandler().post(new Runnable() {
            @Override
            // Overriding the method "run"
            public void run() {
                
                /*
                    Obtain the content Information change through the abstract class "Context" containing method "getContext"
                    imported from "android.content.Context" which invokes the method "getContentResolver" which again invokes 
                    the method "registerContentObserver" imported from "android.database.ContentObserver"
                */
                /*  
                    Checks for the content change in the current overlay display device and if any change is encountered, the 
                    "updateOverlayDisplayDevices" function gets invoked.
                */
                /*
                    "registerContentObserver" takes the arguments "Settings.Global.getUriFor" which gets the content URI
                    for the parameter "Settings.Global.OVERLAY_DISPLAY_DEVICES" which is imported from "android.provider.Settings",
                    boolean value true, and a "ConstructObserver" object imported from "android.database.ContentObserver"
                */
                getContext().getContentResolver().registerContentObserver(
                        
                        Settings.Global.getUriFor(Settings.Global.OVERLAY_DISPLAY_DEVICES),
                        true, new ContentObserver(getHandler()) {
                            @Override
                            /* 
                                Overriding the onChange method defined in class ContentObserver imported from "android.database.ContentObserver"
                                Method gets invoked when there is a content change
                            */
                            public void onChange(boolean selfChange) {
                                // Invoke the method "updateOverlayDisplayDevices" when the parameter "selfChange" is true
                                updateOverlayDisplayDevices();
                            }
                        });
                // Invoke the method "updateOverlayDisplayDevices"
                updateOverlayDisplayDevices();
            }
        });
    }
    
    /* 
        Check wether the control is returned back from the thread using "synchronized" and if true invokes 
        the method "updateOverlayDisplayDevicesLocked" 
    */
    private void updateOverlayDisplayDevices() {
        synchronized (getSyncRoot()) {
            updateOverlayDisplayDevicesLocked();
        }
    }

    // Calculate the mode values for the Display Devices and update the same to the Display Manager
    private void updateOverlayDisplayDevicesLocked() {
        /*
            "getString" retrieves the value from the database using the access controller "getContext().getContentResolver()"
            and the name "Settings.Global.OVERLAY_DISPLAY_DEVICES" is looked up in the database.
        */        
        String value = Settings.Global.getString(getContext().getContentResolver(),
                Settings.Global.OVERLAY_DISPLAY_DEVICES);
        // If the returned value is null, empty string is assigned to string "value"
        if (value == null) {
            value = "";
        }
        
        /* 
            If the Content of the current Display Device is equal to the value of "mCurrentOverlaySetting", 
            the control from this function is returned to its caller.
        */
        if (value.equals(mCurrentOverlaySetting)) {
            return;
        }
        
        // Assigning the Content defined in the database for the Display Device to "mCurrentOverlaySetting"
        mCurrentOverlaySetting = value;
        
        // Checking that Array List "mOverlays" is not empty and update the logs accordingly
        if (!mOverlays.isEmpty()) {
            /*
                Invoking the Information method from class Slog imported from "android.util.Slog"
                Update the log with the Tag "OverlayDisplayAdapter" and the appropriate information message 
            */
            Slog.i(TAG, "Dismissing all overlay display devices.");
            
            /* 
                Iterating over the Array List "mOverlays" and invoking the method "dismissLocked" which removes 
                any pending posts in the thread that  is in the message queue
            */
            for (OverlayDisplayHandle overlay : mOverlays) {
                overlay.dismissLocked();
            }
            
            // Clearing the Array List "mOverlays" post removing the local state of each Display Device
            mOverlays.clear();
        }
        
        // Count variable to keep track of the number of Overlay Display Devices that are active
        int count = 0;
        
        // Iterating each element of value split with the regex ";"
        for (String part : value.split(";")) {
            /* 
                Creating an object "displayMatcher" of type Matcher imported from "java.util.regex.Matcher"
                Each "part" of the "value" string is matched with "DISPLAY_PATTERN" and the value is stored in Matcher object "displayMatcher"
            */
            Matcher displayMatcher = DISPLAY_PATTERN.matcher(part);
            
            // Checks whether the "displayMatcher" matches entirely with the pattern
            if (displayMatcher.matches()) {
                // If the count value exceeds 4 the log is updated with a warning accordingly and the control exits the function
                if (count >= 4) {
                    // Raise a warning message in the log with TAG and the appropriate warning message
                    Slog.w(TAG, "Too many overlay display devices specified: " + value);
                    break;
                }
                /* 
                    "group" function stores the given parameter's subset of string matched with the previous match pattern.
                    modeString contains the all  modes of "part"
                    flagString contains the all  flags of "part"
                */
                String modeString = displayMatcher.group(1);
                String flagString = displayMatcher.group(2);
                
                // Create Array List "modes" of type "OverlayMode" to store all the modes from "modeString"
                ArrayList<OverlayMode> modes = new ArrayList<>();
                
                // Iterating over individual modes by splitting each modes with regexp "\\|" in "modeString"
                for (String mode : modeString.split("\\|")) {
                    // Creating an object "modeMatcher" which matches the "mode" with pre-defined "MODE_PATTERN" 
                    Matcher modeMatcher = MODE_PATTERN.matcher(mode);
                    
                    // Checks whether the "modeMatcher" matches entirely with the pattern
                    if (modeMatcher.matches()) {
                        // Exception Handling for checking Number Format
                        try {
                            /*
                                Width => First match in string upto length of 10 stored as integer
                                Height => Second match in string upto length of 10 stored as integer
                                DensityDpi => Third match in string upto length of 10 stored as integer
                            */
                            int width = Integer.parseInt(modeMatcher.group(1), 10);
                            int height = Integer.parseInt(modeMatcher.group(2), 10);
                            int densityDpi = Integer.parseInt(modeMatcher.group(3), 10);
                            
                            /*
                                Checking whether the width exists between the range of 100 - 4096,
                                Height between the range of 100 - 4096 and DensityDpi between the range of 120 - 640
                                (DENSITY_LOW and DENSITY_XXXHIGH defined in "android.util.DisplayMetrics" for the device
                                resolution between Low-density screens upto 4k television screens)
                                
                                If condition is met, the mode value is added into the Array List "modes" with the 
                                equivalent width, height and DensityDpi and the loop is continued
                            */
                            if (width >= MIN_WIDTH && width <= MAX_WIDTH
                                    && height >= MIN_HEIGHT && height <= MAX_HEIGHT
                                    && densityDpi >= DisplayMetrics.DENSITY_LOW
                                    && densityDpi <= DisplayMetrics.DENSITY_XXXHIGH) {
                                modes.add(new OverlayMode(width, height, densityDpi));
                                continue;
                            } 
                            // If the above condition fails, the log is updated with warning message and the specific mode
                            else {
                                // Updates the log with the TAG value, appropriate warning message and the mode
                                Slog.w(TAG, "Ignoring out-of-range overlay display mode: " + mode);
                            }
                        } catch (NumberFormatException ex) {
                        }
                    }
                    // If mode is empty, the next iteration is called
                    else if (mode.isEmpty()) {
                        continue;
                    }
                }
                
                // Checking that the mode is not empty
                if (!modes.isEmpty()) {
                    // Pre increment the count and assign it to the variable "number"
                    int number = ++count;
                    
                    /*
                        "getContext()" invokes the method "getResources()" ("android.content.Context") which inturn 
                        invokes "getString()" (android.provider.Settings)
                        
                        Returns the corresponding value from the database by matching with "number" and the resolver
                        "com.android.internal.R.string.display_manager_overlay_display_name"
                    */
                    String name = getContext().getResources().getString(
                            com.android.internal.R.string.display_manager_overlay_display_name,
                            number);
                    
                    /* 
                        Call the function "chooseOverlayGravity" with parameter "number" containing the count value
                        which returns the value of the edges of the container based on the "number"
                    */ 
                    int gravity = chooseOverlayGravity(number);
                    
                    // Store the boolean value of (check if flagstring is not null and if it contains the value ",secure") in "secure" variable
                    // Stores the secure flag as a boolean value based on the flag input of the overlay display device
                    boolean secure = flagString != null && flagString.contains(",secure");
                    
                    // Update the log with the TAG value, and the appropriate message with the mode and the count of overlay device
                    Slog.i(TAG, "Showing overlay display device #" + number
                            + ": name=" + name + ", modes=" + Arrays.toString(modes.toArray()));
                    
                    // Adding a new "OverlayDisplayHandle" to the Array List "mOverlays" with the below mentioned parameters.
                    mOverlays.add(new OverlayDisplayHandle(name, modes, gravity, secure, number));
                    continue;
                }
            }
            // Update the log with the warning message "Malformed overlay display device setting"
            Slog.w(TAG, "Malformed overlay display devices setting: " + value);
        }
    }
    
    // Function to compute the edge of the specification and return the same based on the "overlayNumber"
    // Edge specification is defined in "android.view.Gravity"
    private static int chooseOverlayGravity(int overlayNumber) {
        switch (overlayNumber) {
            // "overlayNumber" => 1; Return the top left edge specification
            case 1:
                return Gravity.TOP | Gravity.LEFT;
            // "overlayNumber" => 2; Return the bottom right edge specification
            case 2:
                return Gravity.BOTTOM | Gravity.RIGHT;
            // "overlayNumber" => 3; Return the top right edge specification
            case 3:
                return Gravity.TOP | Gravity.RIGHT;
            // "overlayNumber" => 4; Return the bottom left edge specification
            case 4:
            default:
                return Gravity.BOTTOM | Gravity.LEFT;
        }
    }
    
    // Defining abstract class "OverlayDisplayDevice" which inherits from abstract class "DisplayDevice"
    private abstract class OverlayDisplayDevice extends DisplayDevice {
        // Name of the Overlay Display Device
        private final String mName;
        
        // Refresh Rate of the Overlay Display Device
        private final float mRefreshRate;
        
        // Variable to store the Time to advance the buffer before presentation
        private final long mDisplayPresentationDeadlineNanos;
        
        // Boolean variable to denote whether the Display Device is secure or not
        private final boolean mSecure;
        
        // Array List of type "OverlayMode" that contains width, height, densityDpi of the Overlay Display Device
        private final List<OverlayMode> mRawModes;
        
        // Array of type "Mode" defined in Display.java, int variable to store default mode
        private final Display.Mode[] mModes;
        
        // Default Mode value of the Display Device
        private final int mDefaultMode;

        // Variables to store the state of the Display Device
        private int mState;
        
        // Variable "mSurfaceTexture" to store the frames of image stream
        // (type => SurfaceTexture imported from "android.graphics.SurfaceTexture")
        private SurfaceTexture mSurfaceTexture;
        
        // Raw buffer which is invoked by the image streams(type => Surface imported from "android.view.Surface")
        private Surface mSurface;
        
        // Variables to store the display device Information and also to store the Active modes
        private DisplayDeviceInfo mInfo;
        
        // Active mode storage
        private int mActiveMode;
        
        /* 
            Constructor taking arguments for both parent class and the sub class
            The parent class constructor is invoked by the super method that passes values to the parent class variables.
        */
        public OverlayDisplayDevice(IBinder displayToken, String name,
                List<OverlayMode> modes, int activeMode, int defaultMode,
                float refreshRate, long presentationDeadlineNanos,
                boolean secure, int state,
                SurfaceTexture surfaceTexture, int number) {
            // Invoking the super class constructor with the appropriate parameter values
            super(OverlayDisplayAdapter.this, displayToken, UNIQUE_ID_PREFIX + number);
            
            // Assigning the parameter values to the class variables during object initialization
            mName = name;
            mRefreshRate = refreshRate;
            mDisplayPresentationDeadlineNanos = presentationDeadlineNanos;
            mSecure = secure;
            mState = state;
            mSurfaceTexture = surfaceTexture;
            mRawModes = modes;
            // Creating an object Display.Mode imported from android.view.Display taking the parameter as mode size
            mModes = new Display.Mode[modes.size()];
            
            /*  
                Iterating from 0 - size of the modes Array List, each index of "mModes" is set the the value of the specific
                "mwidth", "mHeight", "refreshRate"
            */
            for (int i = 0; i < modes.size(); i++) {
                OverlayMode mode = modes.get(i);
                mModes[i] = createMode(mode.mWidth, mode.mHeight, refreshRate);
            }
            
            // Storing the active mode and default mode during object initialization
            mActiveMode = activeMode;
            mDefaultMode = defaultMode;
        }
        
        // Function to set the Surface texture and the Surface values to null
        public void destroyLocked() {
            mSurfaceTexture = null;
            // If the "mSurface" is not null, the local reference for that surface is set to null and becomes invalid
            // Function "release()" is defined under "android.view.Surface"
            if (mSurface != null) {
                mSurface.release();
                mSurface = null;
            }
            // "getDisplayTokenLocked()" returns the "mDisplayToken" of the parent class Display Device
            // Makes the Display Token invalid and sets it to null (Defined in android.view.SurfaceControl)
            SurfaceControl.destroyDisplay(getDisplayTokenLocked());
        }
        
        
        // Overriding the method "hasStableUniqueId" declared in parent class
        @Override
        // Checks whether the Unique ID of the Device is stable across reboots and returns the boolean value accordingly
        public boolean hasStableUniqueId() {
            return false;
        }
        
        
        // Overriding the method "performTraversalInTransactionLocked" declared in parent class
        @Override
        // Update the display device properties while in transaction(while it is being accessed)
        public void performTraversalInTransactionLocked() {
            // Condition to check if the Surface Texture object is not null
            if (mSurfaceTexture != null) {
                /*
                    Condition to check if the Surface Object is null and if it is then a new object is referenced to the same
                    with the surfaceTexture object
                */
                if (mSurface == null) {
                    mSurface = new Surface(mSurfaceTexture);
                }
                
                /* 
                    Call the parent class method "setSurfaceInTransactionLocked" with the parameter "mSurface" which updates the 
                    properties of the display device while in transaction    
                */
                setSurfaceInTransactionLocked(mSurface);
            }
        }
        
        
        // Setting the state of the Display Device with the state value passed as parameter and the info is set to null
        public void setStateLocked(int state) {
            mState = state;
            mInfo = null;
        }
        
        
        // Overriding the "getDisplayDeviceInfoLocked" defined in class "DisplayDeviceInfo" in the parent class
        @Override
        // Function to return the Display Device Info which is immutable and of type "DisplayDeviceInfo"
        public DisplayDeviceInfo getDisplayDeviceInfoLocked() {
            // Checking if the mInfo is null
            if (mInfo == null) {
                // Set the Mode object "mode" with the value in the array "mModes" at index "mActiveMode"
                Display.Mode mode = mModes[mActiveMode];
                
                // Set the OverlayMode object "rawMode" with the Array List value mRawModes at index "mActiveMode"
                OverlayMode rawMode = mRawModes.get(mActiveMode);
                
                /* 
                    Creating the new Display Device Info Object mInfo and setting the name, uniqueID, width, height
                    "getUniqueId" defined in parent class retrieves the Unique ID of the display device
                    "getPhysicalWidth" defined in Device Class retrieves the physical width
                    "getPhysicalHeight" defined in Device Class retrieves the physical height
                */
                mInfo = new DisplayDeviceInfo();
                mInfo.name = mName;
                mInfo.uniqueId = getUniqueId();
                mInfo.width = mode.getPhysicalWidth();
                mInfo.height = mode.getPhysicalHeight();
                
                /*
                    Set the modeID, default mode ID, supported modes, density DPI
                    "getModeId" defined in Display class returns the ID of the mode
                    "mDensityDpi" is defined in OverlayMode class
                */
                mInfo.modeId = mode.getModeId();
                mInfo.defaultModeId = mModes[0].getModeId();
                mInfo.supportedModes = mModes;
                mInfo.densityDpi = rawMode.mDensityDpi;
                mInfo.xDpi = rawMode.mDensityDpi;
                mInfo.yDpi = rawMode.mDensityDpi;
                
                // Set the value of "presentationDeadlineNanos" based on the refresh rate and the time to advance the buffer for the
                // display device
                mInfo.presentationDeadlineNanos = mDisplayPresentationDeadlineNanos +
                        1000000000L / (int) mRefreshRate;   // display's deadline + 1 frame
                // FLAG_PRESENTATION = 1 << 6 (Defined in DisplayDeviceInfo.java)
                mInfo.flags = DisplayDeviceInfo.FLAG_PRESENTATION;
                
                // if mSecure is true the flag value is logically OR'ed with FLAG_SECURE => 1 << 2 (Defined in DisplayDeviceInfo.java)
                if (mSecure) {
                    mInfo.flags |= DisplayDeviceInfo.FLAG_SECURE;
                }
                
                /*
                    Setting the value of "type", "touch" and "state"
                    "TYPE_OVERLAY" => 4 (Defined in Display.java); "TOUCH_NONE" => 0; (Defined in DisplayDeviceInfo.java)
                */
                mInfo.type = Display.TYPE_OVERLAY;
                mInfo.touch = DisplayDeviceInfo.TOUCH_NONE;
                mInfo.state = mState;
            }
            // Return the Information of the display device
            return mInfo;
        }
        
        
        // Overriding the function defined in the parent class
        @Override
        // Setting the mode only if supported
        // Color and ID of the device is passed as parameter
        public void requestDisplayModesInTransactionLocked(int color, int id) {
            // Default initialization to -1, so that the array index does not clash with the default "index" value
            int index = -1;
            
            // If ID of the device is 0, set the index to 0
            if (id == 0) {
                // Use the default.
                index = 0;
            }
            // If index is other than 0
            else {
                /* 
                    Iterating through the length of "mModes" Array, if the input parameter id value is equal to the modeID 
                    of the mode in that index, then the index value is stored in the "index" variable and the function is exited.
                */
                for (int i = 0; i < mModes.length; i++) {
                    if (mModes[i].getModeId() == id) {
                        index = i;
                        break;
                    }
                }
            }
            
            // If index is -1, a warning log is created and Default mode value is set to index;
            // If mode is not found;
            if (index == -1) {
                // Create the warning log, with the TAG, and the warning message "Unable to locate the mode"
                Slog.w(TAG, "Unable to locate mode " + id + ", reverting to default.");
                
                // Setting the index to Default mode
                index = mDefaultMode;
            }
            
            // Return from the function control, if the Active Mode is equal to the "index" value
            if (mActiveMode == index) {
                return;
            }
            
            // Setting the "index" value to "mActiveMode"
            mActiveMode = index;
            mInfo = null;
            
            // Sends the display device event to the Display Adapter asynchronously(Defined in Parent class)
            sendDisplayDeviceEventLocked(this, DISPLAY_DEVICE_EVENT_CHANGED);
            
            // Calls when device switches to a new mode and "index" value is passed as parameter which denotes the index of the mode
            onModeChangedLocked(index);
        }

        /**
         * Called when the device switched to a new mode.
         *
         * @param index index of the mode in the list of modes
         */
        public abstract void onModeChangedLocked(int index);
    }

    /**
     * Functions as a handle for overlay display devices which are created and
     * destroyed asynchronously.
     *
     * Guarded by the {@link DisplayManagerService.SyncRoot} lock.
     */
    private final class OverlayDisplayHandle implements OverlayDisplayWindow.Listener {
        // Setting Default mode index to be 0
        private static final int DEFAULT_MODE_INDEX = 0;
        
        // Variable to store the Overlay Display Handle Name
        private final String mName;
        
        // Array List of type "OverlayMode" to store the modes
        private final List<OverlayMode> mModes;
        
        // Variable to store the gravity, secure(boolean) and number of the Overlay Display Handle
        private final int mGravity;
        private final boolean mSecure;
        private final int mNumber;
        
        // Variable to store the Window and Device information and also the Active mode value
        private OverlayDisplayWindow mWindow;
        private OverlayDisplayDevice mDevice;
        private int mActiveMode;
        
        
        // Constructor taking arguments for the class and values are initialized accordingly
        public OverlayDisplayHandle(String name, List<OverlayMode> modes, int gravity,
                boolean secure, int number) {
            // Assigning the parameter values to the class variables during object initialization
            mName = name;
            mModes = modes;
            mGravity = gravity;
            mSecure = secure;
            mNumber = number;
            
            // Setting the active mode to 0 when the object is initialized
            mActiveMode = 0;
            
            // Invoke the "showLocked" as soon as the object for the class is initialized
            showLocked();
        }
        
        // Method to post to the handler, the status of the running Thread
        private void showLocked() {
            mUiHandler.post(mShowRunnable);
        }
        
        // Method to remove the pending posts in the running Thread and post the status to the Handler
        public void dismissLocked() {
            mUiHandler.removeCallbacks(mShowRunnable);
            // "mDismissRunnable" is an object defined in class OverlayDisplayWindow which contains the window value as null and the 
            // subsequent window dismissed
            mUiHandler.post(mDismissRunnable);
        }
        
        // Method to remove the pending posts of the Resizable window and post the status to the Handler
        // "index" denotes the Active mode of the device
        private void onActiveModeChangedLocked(int index) {
            mUiHandler.removeCallbacks(mResizeRunnable);
            mActiveMode = index;
            // Condition to check if the mWindow is not null and if it's true, the resulting window is posted to the Handler
            if (mWindow != null) {
                mUiHandler.post(mResizeRunnable);
            }
        }

        // Called on the UI thread.
        // Function overriding the declaration from the interface "OverlayDisplayWindow.Listener"
        @Override
        // Function to update the display device event with the event "DISPLAY_DEVICE_EVENT_ADDED" upon window creation 
        // "surfaceTexture" holds the value of the frames of the image stream
        // "refreshRate" hold the refresh rate of the display device
        // "presentationDeadlineNanos" stores the time to advance the buffer value
        // "state" stores the state of the display device
        public void onWindowCreated(SurfaceTexture surfaceTexture, float refreshRate,
                long presentationDeadlineNanos, int state) {
            synchronized (getSyncRoot()) {
                // Creating Display using "mName" & "mSecure" of SurfaceControl class and assigning the value to displayToken of type Ibinder
                // IBinder is defined in "android.os.IBinder"
                IBinder displayToken = SurfaceControl.createDisplay(mName, mSecure);
                
                // Assigning a new object reference to the "mDevice"
                mDevice = new OverlayDisplayDevice(displayToken, mName, mModes, mActiveMode,
                        DEFAULT_MODE_INDEX, refreshRate, presentationDeadlineNanos,
                        mSecure, state, surfaceTexture, mNumber) {
                    @Override
                    // Update the Mode change with the new value and post the resulting call backs to the Handler
                    public void onModeChangedLocked(int index) {
                        onActiveModeChangedLocked(index);
                    }
                };
                // Sends the display device event "DISPLAY_DEVICE_EVENT_ADDED" to the Display Adapter asynchronously
                sendDisplayDeviceEventLocked(mDevice, DISPLAY_DEVICE_EVENT_ADDED);
            }
        }
        

        // Called on the UI thread.
        // Function overriding the declaration from the interface "OverlayDisplayWindow.Listener"
        @Override
        // Function to update the display device event with the event "DISPLAY_DEVICE_EVENT_REMOVED" upon window deallocation 
        public void onWindowDestroyed() {
            // Check whether the control returned from the Thread
            synchronized (getSyncRoot()) {
                // If the mDevice object is not null, the destroyLocked function is invoked which sets the 
                // "mSurfaceTexture " & "mSurface" to null and releases the "mSurface"
                if (mDevice != null) {
                    mDevice.destroyLocked();
                    // Sends the display device event "DISPLAY_DEVICE_EVENT_REMOVED" to the Display Adapter "mDevice" asynchronously
                    sendDisplayDeviceEventLocked(mDevice, DISPLAY_DEVICE_EVENT_REMOVED);
                }
            }
        }

        
        // Called on the UI thread.
        // Function overriding the declaration from the interface "OverlayDisplayWindow.Listener"
        @Override
        // Function to update the display device event with the event "DISPLAY_DEVICE_EVENT_CHANGED" upon window state change
        public void onStateChanged(int state) {
            synchronized (getSyncRoot()) {
                // If the mDevice object is not null, the setStateLocked function is invoked which sets the 
                // "mState" value with "state"
                if (mDevice != null) {
                    mDevice.setStateLocked(state);
                    // Sends the display device event "DISPLAY_DEVICE_EVENT_CHANGED" to the Display Adapter asynchronously
                    sendDisplayDeviceEventLocked(mDevice, DISPLAY_DEVICE_EVENT_CHANGED);
                }
            }
        }
        
        // Dumps the local state of the invoking class object
        public void dumpLocked(PrintWriter pw) {
            /*  
                PrintWriter prints the values in text-output stream to the console.
                Name, Modes, Active Mode, Gravity, Secure and Number are printed to the console
            */
            pw.println("  " + mName + ":");
            pw.println("    mModes=" + Arrays.toString(mModes.toArray()));
            pw.println("    mActiveMode=" + mActiveMode);
            pw.println("    mGravity=" + mGravity);
            pw.println("    mSecure=" + mSecure);
            pw.println("    mNumber=" + mNumber);

            // Try to dump the window state.
            if (mWindow != null) {
                // Create an object "ipw" of type "IndentingPrintWriter" imported from "com.android.internal.util.IndentingPrintWriter"
                // Mentioning 4 spaces as the indent length which is added to the object "ipw" using "increaseIndent"
                final IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "    ");
                
                // Increasing the Indent for the object ipw
                ipw.increaseIndent();
                
                // Timeout of 200ms is provided to avoid a deadlock
                // (DumpUtils is imported from "com.android.internal.util.DumpUtils")
                DumpUtils.dumpAsync(mUiHandler, mWindow, ipw, "", 200);
            }
        }

        // Runs on the UI thread.
        // "mShowRunnable" defined as type "Runnable" (defined in Runnable.java) which reduces code duplication when using multiple sessions
        private final Runnable mShowRunnable = new Runnable() {
            @Override
            public void run() {
                // "mode" is set the Active mode value
                OverlayMode mode = mModes.get(mActiveMode);
                
                // New "window" object of type "OverlayDisplayWindow" is created with the name, width, height, densitydpi, gravity, 
                // secure, "OverlayDisplayHandle"
                OverlayDisplayWindow window = new OverlayDisplayWindow(getContext(),
                        mName, mode.mWidth, mode.mHeight, mode.mDensityDpi, mGravity, mSecure,
                        OverlayDisplayHandle.this);
                
                // Add a view to the Window Manager and Clear the Live state from the Window (Defined in parent class)
                window.show();
                
                // Setting "window" object to "mWindow" if the control from thread has returned
                synchronized (getSyncRoot()) {
                    mWindow = window;
                }
            }
        };

        // Runs on the UI thread.
        // "mDismissRunnable" defined as type "Runnable" which reduces code duplication when using multiple sessions
        private final Runnable mDismissRunnable = new Runnable() {
            @Override
            public void run() {
                OverlayDisplayWindow window;
                
                // Setting "window" object to "mWindow" if the control from thread has returned and setting "mWindow" to null
                synchronized (getSyncRoot()) {
                    window = mWindow;
                    mWindow = null;
                }
                
                // If window is not null, the current view is removed from the Window Manager (Defined in parent class)
                if (window != null) {
                    window.dismiss();
                }
            }
        };

        // Runs on the UI thread.
        // "mResizeRunnable" defined as type "Runnable" which reduces code duplication when using multiple sessions
        private final Runnable mResizeRunnable = new Runnable() {
            @Override
            public void run() {
                OverlayMode mode;
                OverlayDisplayWindow window;
                
                // If "window" is null and the control from thread has returned, the control from function is returned to its caller
                synchronized (getSyncRoot()) {
                    if (mWindow == null) {
                        return;
                    }
                    
                    // "mode" is set to the Active mode value from mModes Array and "window" is set to "mWindow"
                    mode = mModes.get(mActiveMode);
                    window = mWindow;
                }
                
                // Resizing the current active window with the mentioned width, height and DensityDPI
                window.resize(mode.mWidth, mode.mHeight, mode.mDensityDpi);
            }
        };
    }

    /**
     * A display mode for an overlay display.
     */
    // Declaring Class "OverlayMode" to update the new values in appended string formats
    private static final class OverlayMode {
        final int mWidth;
        final int mHeight;
        final int mDensityDpi;
        
        // Constructor to initialize the width, height and densityDpi to respective class variables
        OverlayMode(int width, int height, int densityDpi) {
            mWidth = width;
            mHeight = height;
            mDensityDpi = densityDpi;
        }
        
        // Overriding the function defined in DisplayDeviceInfo.java
        @Override
        // Method for printing the values in string format and returning the same
        public String toString() {
            
            // Building a new string of type "StringBuilder" contining the values of width, height and densityDpi in a specific format
            // Format => {width=mWidth, height=mHeight, densityDpi=mDensityDpi}
            return new StringBuilder("{")
                    .append("width=").append(mWidth)
                    .append(", height=").append(mHeight)
                    .append(", densityDpi=").append(mDensityDpi)
                    .append("}")
                    .toString();
        }
    }
}
