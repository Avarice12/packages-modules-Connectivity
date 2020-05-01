/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.net.ipsec.ike.cts;

import static android.app.AppOpsManager.OP_MANAGE_IPSEC_TUNNELS;

import android.annotation.NonNull;
import android.app.AppOpsManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.InetAddresses;
import android.net.IpSecTransform;
import android.net.LinkAddress;
import android.net.Network;
import android.net.TestNetworkInterface;
import android.net.TestNetworkManager;
import android.net.annotations.PolicyDirection;
import android.net.ipsec.ike.ChildSessionCallback;
import android.net.ipsec.ike.ChildSessionConfiguration;
import android.net.ipsec.ike.IkeSessionCallback;
import android.net.ipsec.ike.IkeSessionConfiguration;
import android.net.ipsec.ike.IkeTrafficSelector;
import android.net.ipsec.ike.cts.TestNetworkUtils.TestNetworkCallback;
import android.net.ipsec.ike.exceptions.IkeException;
import android.net.ipsec.ike.exceptions.IkeProtocolException;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.platform.test.annotations.AppModeFull;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.compatibility.common.util.SystemUtil;
import com.android.testutils.ArrayTrackRecord;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.runner.RunWith;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Package private base class for testing IkeSessionParams and IKE exchanges.
 *
 * <p>Subclasses MUST explicitly call #setUpTestNetwork and #tearDownTestNetwork to be able to use
 * the test network
 */
@RunWith(AndroidJUnit4.class)
@AppModeFull(reason = "MANAGE_TEST_NETWORKS permission can't be granted to instant apps")
abstract class IkeSessionTestBase extends IkeTestBase {
    // Package-wide common expected results that will be shared by all IKE/Child SA creation tests
    static final String EXPECTED_REMOTE_APP_VERSION_EMPTY = "";
    static final byte[] EXPECTED_PROTOCOL_ERROR_DATA_NONE = new byte[0];
    static final InetAddress EXPECTED_INTERNAL_ADDR =
            InetAddresses.parseNumericAddress("198.51.100.10");
    static final LinkAddress EXPECTED_INTERNAL_LINK_ADDR =
            new LinkAddress(EXPECTED_INTERNAL_ADDR, IP4_PREFIX_LEN);
    static final IkeTrafficSelector EXPECTED_INBOUND_TS =
            new IkeTrafficSelector(
                    MIN_PORT, MAX_PORT, EXPECTED_INTERNAL_ADDR, EXPECTED_INTERNAL_ADDR);

    // Static state to reduce setup/teardown
    static Context sContext = InstrumentationRegistry.getContext();
    static ConnectivityManager sCM =
            (ConnectivityManager) sContext.getSystemService(Context.CONNECTIVITY_SERVICE);
    static TestNetworkManager sTNM;

    private static final int TIMEOUT_MS = 500;

    // Constants to be used for providing different IP addresses for each tests
    private static final byte IP_ADDR_LAST_BYTE_MAX = (byte) 100;
    private static final byte[] INITIAL_AVAILABLE_IP4_ADDR_LOCAL =
            InetAddresses.parseNumericAddress("192.0.2.1").getAddress();
    private static final byte[] INITIAL_AVAILABLE_IP4_ADDR_REMOTE =
            InetAddresses.parseNumericAddress("198.51.100.1").getAddress();
    private static final byte[] NEXT_AVAILABLE_IP4_ADDR_LOCAL = INITIAL_AVAILABLE_IP4_ADDR_LOCAL;
    private static final byte[] NEXT_AVAILABLE_IP4_ADDR_REMOTE = INITIAL_AVAILABLE_IP4_ADDR_REMOTE;

    ParcelFileDescriptor mTunFd;
    TestNetworkCallback mTunNetworkCallback;
    Network mTunNetwork;
    IkeTunUtils mTunUtils;

    InetAddress mLocalAddress;
    InetAddress mRemoteAddress;

    Executor mUserCbExecutor;
    TestIkeSessionCallback mIkeSessionCallback;
    TestChildSessionCallback mFirstChildSessionCallback;

    // This method is guaranteed to run in subclasses and will run before subclasses' @BeforeClass
    // methods.
    @BeforeClass
    public static void setUpPermissionBeforeClass() throws Exception {
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .adoptShellPermissionIdentity();
        sTNM = (TestNetworkManager) sContext.getSystemService(Context.TEST_NETWORK_SERVICE);

        // Under normal circumstances, the MANAGE_IPSEC_TUNNELS appop would be auto-granted, and
        // a standard permission is insufficient. So we shell out the appop, to give us the
        // right appop permissions.
        setAppOp(OP_MANAGE_IPSEC_TUNNELS, true);
    }

    // This method is guaranteed to run in subclasses and will run after subclasses' @AfterClass
    // methods.
    @AfterClass
    public static void tearDownPermissionAfterClass() throws Exception {
        setAppOp(OP_MANAGE_IPSEC_TUNNELS, false);

        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .dropShellPermissionIdentity();
    }

    @Before
    public void setUp() throws Exception {
        mLocalAddress = getNextAvailableIpv4AddressLocal();
        mRemoteAddress = getNextAvailableIpv4AddressRemote();
        setUpTestNetwork(mLocalAddress);

        mUserCbExecutor = Executors.newSingleThreadExecutor();
        mIkeSessionCallback = new TestIkeSessionCallback();
        mFirstChildSessionCallback = new TestChildSessionCallback();
    }

    @After
    public void tearDown() throws Exception {
        tearDownTestNetwork();

        resetNextAvailableAddress(NEXT_AVAILABLE_IP4_ADDR_LOCAL, INITIAL_AVAILABLE_IP4_ADDR_LOCAL);
        resetNextAvailableAddress(
                NEXT_AVAILABLE_IP4_ADDR_REMOTE, INITIAL_AVAILABLE_IP4_ADDR_REMOTE);
    }

    void setUpTestNetwork(InetAddress localAddr) throws Exception {
        int prefixLen = localAddr instanceof Inet4Address ? IP4_PREFIX_LEN : IP4_PREFIX_LEN;

        TestNetworkInterface testIface =
                sTNM.createTunInterface(new LinkAddress[] {new LinkAddress(localAddr, prefixLen)});

        mTunFd = testIface.getFileDescriptor();
        mTunNetworkCallback =
                TestNetworkUtils.setupAndGetTestNetwork(
                        sCM, sTNM, testIface.getInterfaceName(), new Binder());
        mTunNetwork = mTunNetworkCallback.getNetworkBlocking();
        mTunUtils = new IkeTunUtils(mTunFd);
    }

    void tearDownTestNetwork() throws Exception {
        sCM.unregisterNetworkCallback(mTunNetworkCallback);

        sTNM.teardownTestNetwork(mTunNetwork);
        mTunFd.close();
    }

    private static void setAppOp(int appop, boolean allow) {
        String opName = AppOpsManager.opToName(appop);
        for (String pkg : new String[] {"com.android.shell", sContext.getPackageName()}) {
            String cmd =
                    String.format(
                            "appops set %s %s %s",
                            pkg, // Package name
                            opName, // Appop
                            (allow ? "allow" : "deny")); // Action
            Log.d("IKE", "CTS setAppOp cmd " + cmd);

            String result = SystemUtil.runShellCommand(cmd);
        }
    }

    Inet4Address getNextAvailableIpv4AddressLocal() throws Exception {
        return (Inet4Address)
                getNextAvailableAddress(
                        NEXT_AVAILABLE_IP4_ADDR_LOCAL,
                        INITIAL_AVAILABLE_IP4_ADDR_LOCAL,
                        false /* isIp6 */);
    }

    Inet4Address getNextAvailableIpv4AddressRemote() throws Exception {
        return (Inet4Address)
                getNextAvailableAddress(
                        NEXT_AVAILABLE_IP4_ADDR_REMOTE,
                        INITIAL_AVAILABLE_IP4_ADDR_REMOTE,
                        false /* isIp6 */);
    }

    InetAddress getNextAvailableAddress(
            byte[] nextAddressBytes, byte[] initialAddressBytes, boolean isIp6) throws Exception {
        int addressLen = isIp6 ? IP6_ADDRESS_LEN : IP4_ADDRESS_LEN;

        synchronized (nextAddressBytes) {
            if (nextAddressBytes[addressLen - 1] == IP_ADDR_LAST_BYTE_MAX) {
                resetNextAvailableAddress(nextAddressBytes, initialAddressBytes);
            }

            InetAddress address = InetAddress.getByAddress(nextAddressBytes);
            nextAddressBytes[addressLen - 1]++;
            return address;
        }
    }

    private void resetNextAvailableAddress(byte[] nextAddressBytes, byte[] initialAddressBytes) {
        synchronized (nextAddressBytes) {
            System.arraycopy(
                    nextAddressBytes, 0, initialAddressBytes, 0, initialAddressBytes.length);
        }
    }

    static class TestIkeSessionCallback implements IkeSessionCallback {
        private CompletableFuture<IkeSessionConfiguration> mFutureIkeConfig =
                new CompletableFuture<>();
        private CompletableFuture<Boolean> mFutureOnClosedCall = new CompletableFuture<>();
        private CompletableFuture<IkeException> mFutureOnClosedException =
                new CompletableFuture<>();

        private int mOnErrorExceptionsCount = 0;
        private ArrayTrackRecord<IkeProtocolException> mOnErrorExceptionsTrackRecord =
                new ArrayTrackRecord<>();

        @Override
        public void onOpened(@NonNull IkeSessionConfiguration sessionConfiguration) {
            mFutureIkeConfig.complete(sessionConfiguration);
        }

        @Override
        public void onClosed() {
            mFutureOnClosedCall.complete(true /* unused */);
        }

        @Override
        public void onClosedExceptionally(@NonNull IkeException exception) {
            mFutureOnClosedException.complete(exception);
        }

        @Override
        public void onError(@NonNull IkeProtocolException exception) {
            mOnErrorExceptionsTrackRecord.add(exception);
        }

        public IkeSessionConfiguration awaitIkeConfig() throws Exception {
            return mFutureIkeConfig.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }

        public IkeException awaitOnClosedException() throws Exception {
            return mFutureOnClosedException.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }

        public IkeProtocolException awaitNextOnErrorException() {
            return mOnErrorExceptionsTrackRecord.poll(
                    (long) TIMEOUT_MS,
                    mOnErrorExceptionsCount++,
                    (transform) -> {
                        return true;
                    });
        }

        public void awaitOnClosed() throws Exception {
            mFutureOnClosedCall.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
    }

    static class TestChildSessionCallback implements ChildSessionCallback {
        private CompletableFuture<ChildSessionConfiguration> mFutureChildConfig =
                new CompletableFuture<>();
        private CompletableFuture<Boolean> mFutureOnClosedCall = new CompletableFuture<>();
        private CompletableFuture<IkeException> mFutureOnClosedException =
                new CompletableFuture<>();

        private int mCreatedIpSecTransformCount = 0;
        private int mDeletedIpSecTransformCount = 0;
        private ArrayTrackRecord<IpSecTransformCallRecord> mCreatedIpSecTransformsTrackRecord =
                new ArrayTrackRecord<>();
        private ArrayTrackRecord<IpSecTransformCallRecord> mDeletedIpSecTransformsTrackRecord =
                new ArrayTrackRecord<>();

        @Override
        public void onOpened(@NonNull ChildSessionConfiguration sessionConfiguration) {
            mFutureChildConfig.complete(sessionConfiguration);
        }

        @Override
        public void onClosed() {
            mFutureOnClosedCall.complete(true /* unused */);
        }

        @Override
        public void onClosedExceptionally(@NonNull IkeException exception) {
            mFutureOnClosedException.complete(exception);
        }

        @Override
        public void onIpSecTransformCreated(@NonNull IpSecTransform ipSecTransform, int direction) {
            mCreatedIpSecTransformsTrackRecord.add(
                    new IpSecTransformCallRecord(ipSecTransform, direction));
        }

        @Override
        public void onIpSecTransformDeleted(@NonNull IpSecTransform ipSecTransform, int direction) {
            mDeletedIpSecTransformsTrackRecord.add(
                    new IpSecTransformCallRecord(ipSecTransform, direction));
        }

        public ChildSessionConfiguration awaitChildConfig() throws Exception {
            return mFutureChildConfig.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }

        public IkeException awaitOnClosedException() throws Exception {
            return mFutureOnClosedException.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }

        public IpSecTransformCallRecord awaitNextCreatedIpSecTransform() {
            return mCreatedIpSecTransformsTrackRecord.poll(
                    (long) TIMEOUT_MS,
                    mCreatedIpSecTransformCount++,
                    (transform) -> {
                        return true;
                    });
        }

        public IpSecTransformCallRecord awaitNextDeletedIpSecTransform() {
            return mDeletedIpSecTransformsTrackRecord.poll(
                    (long) TIMEOUT_MS,
                    mDeletedIpSecTransformCount++,
                    (transform) -> {
                        return true;
                    });
        }

        public void awaitOnClosed() throws Exception {
            mFutureOnClosedCall.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * This class represents a created or deleted IpSecTransfrom that is provided by
     * ChildSessionCallback
     */
    static class IpSecTransformCallRecord {
        public final IpSecTransform ipSecTransform;
        public final int direction;

        IpSecTransformCallRecord(IpSecTransform ipSecTransform, @PolicyDirection int direction) {
            this.ipSecTransform = ipSecTransform;
            this.direction = direction;
        }
    }

    // TODO(b/148689509): Verify IKE Session setup using EAP and digital-signature-based auth

    // TODO(b/148689509): Verify hostname based creation
}
