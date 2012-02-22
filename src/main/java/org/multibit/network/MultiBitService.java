/**
 * Copyright 2011 multibit.org
 *
 * Licensed under the MIT license (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://opensource.org/licenses/mit-license.php
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.multibit.network;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Date;

import javax.swing.SwingWorker;

import org.multibit.controller.MultiBitController;
import org.multibit.model.MultiBitModel;
import org.multibit.model.PerWalletModelData;
import org.multibit.model.WalletInfo;
import org.multibit.store.ReplayableBlockStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.bitcoin.core.Address;
import com.google.bitcoin.core.AddressFormatException;
import com.google.bitcoin.core.Block;
import com.google.bitcoin.core.BlockChain;
import com.google.bitcoin.core.ECKey;
import com.google.bitcoin.core.NetworkParameters;
import com.google.bitcoin.core.PeerAddress;
import com.google.bitcoin.core.PeerEventListener;
import com.google.bitcoin.core.PeerGroup;
import com.google.bitcoin.core.StoredBlock;
import com.google.bitcoin.core.Transaction;
import com.google.bitcoin.core.Utils;
import com.google.bitcoin.core.Wallet;
import com.google.bitcoin.discovery.DnsDiscovery;
import com.google.bitcoin.discovery.IrcDiscovery;
import com.google.bitcoin.store.BlockStore;
import com.google.bitcoin.store.BlockStoreException;

/**
 * <p>
 * MultiBitService encapsulates the interaction with the bitcoin netork
 * including: o Peers o Block chain download o sending / receiving bitcoins
 * 
 * It is based on the bitcoinj PingService code
 * 
 * The testnet can be slow or flaky as it's a shared resource. You can use the
 * <a href="http://sourceforge
 * .net/projects/bitcoin/files/Bitcoin/testnet-in-a-box/">testnet in a box</a>
 * to do everything purely locally.
 * </p>
 */
public class MultiBitService {
    private static final Logger log = LoggerFactory.getLogger(MultiBitService.class);

    private static final int NUMBER_OF_MILLISECOND_IN_A_SECOND = 1000;

    private static final int MAXIMUM_EXPECTED_LENGTH_OF_ALTERNATE_CHAIN = 6;

    public static final String MULTIBIT_PREFIX = "multibit";
    public static final String TEST_NET_PREFIX = "testnet";
    public static final String SEPARATOR = "-";

    public static final String BLOCKCHAIN_SUFFIX = ".blockchain";
    public static final String WALLET_SUFFIX = ".wallet";

    public static final String IRC_CHANNEL_TEST = "#bitcoinTEST";;

    public Logger logger = LoggerFactory.getLogger(MultiBitService.class.getName());

    private Wallet wallet;

    private MultiBitPeerGroup peerGroup;

    private String blockchainFilename;

    private BlockChain blockChain;

    private ReplayableBlockStore blockStore;

    public ReplayableBlockStore getBlockStore() {
        return blockStore;
    }

    private boolean useTestNet;

    private MultiBitController controller;

    private final NetworkParameters networkParameters;

    /**
     * 
     * @param useTestNet
     *            true = test net, false = production
     * @param controller
     *            MutliBitController
     */
    public MultiBitService(boolean useTestNet, MultiBitController controller) {
        this(useTestNet, controller.getModel().getUserPreference(MultiBitModel.WALLET_FILENAME), controller);
    }

    /**
     * 
     * @param useTestNet
     *            true = test net, false = production
     * @param walletFilename
     *            filename of current wallet
     * @param controller
     *            MutliBitController
     */
    public MultiBitService(boolean useTestNet, String walletFilename, MultiBitController controller) {
        this.useTestNet = useTestNet;
        this.controller = controller;

        networkParameters = useTestNet ? NetworkParameters.testNet() : NetworkParameters.prodNet();

        try {
            // Load the block chain
            String filePrefix = getFilePrefix();
            if ("".equals(controller.getApplicationDataDirectoryLocator().getApplicationDataDirectory())) {
                blockchainFilename = filePrefix + BLOCKCHAIN_SUFFIX;
            } else {
                blockchainFilename = controller.getApplicationDataDirectoryLocator().getApplicationDataDirectory() + File.separator
                        + filePrefix + BLOCKCHAIN_SUFFIX;
            }

            // check to see if the user has a blockchain and copy over the
            // installed one if they do not
            controller.getFileHandler().copyBlockChainFromInstallationDirectory(this, blockchainFilename);

            log.debug("Reading block store '{}' from disk", blockchainFilename);

            blockStore = new ReplayableBlockStore(networkParameters, new File(blockchainFilename), false);

            log.debug("Connecting ...");
            blockChain = new BlockChain(networkParameters, blockStore);

            peerGroup = new MultiBitPeerGroup(controller, networkParameters, blockChain);
            peerGroup.setFastCatchupTimeSecs(0); // genesis block
            peerGroup.setUserAgent("MultiBit", controller.getLocaliser().getVersionNumber());

            String singleNodeConnection = controller.getModel().getUserPreference(MultiBitModel.SINGLE_NODE_CONNECTION);
            if (singleNodeConnection != null && !singleNodeConnection.equals("")) {
                try {
                    peerGroup.addAddress(new PeerAddress(InetAddress.getByName(singleNodeConnection)));
                    peerGroup.setMaxConnections(1);
                } catch (UnknownHostException e) {
                    log.error(e.getMessage(), e);

                }
            } else {
                // use DNS for production, IRC for test
                if (useTestNet) {
                    peerGroup.addPeerDiscovery(new IrcDiscovery(IRC_CHANNEL_TEST));
                } else {
                    peerGroup.addPeerDiscovery(new DnsDiscovery(networkParameters));
                }
            }
            // add the controller as a PeerEventListener
            peerGroup.addEventListener(controller);
            peerGroup.start();
        } catch (BlockStoreException e) {
            controller.displayMessage("multiBitService.errorText", new Object[] { e.getClass().getName() + " " + e.getMessage() },
                    "multiBitService.errorTitleText");
        } catch (Exception e) {
            controller.displayMessage("multiBitService.errorText", new Object[] { e.getClass().getName() + " " + e.getMessage() },
                    "multiBitService.errorTitleText");
        }
    }

    public String getFilePrefix() {
        return useTestNet ? MULTIBIT_PREFIX + SEPARATOR + TEST_NET_PREFIX : MULTIBIT_PREFIX;
    }

    /**
     * initialise wallet from the wallet filename
     * 
     * @param walletFilename
     * @return perWalletModelData
     */
    public PerWalletModelData addWalletFromFilename(String walletFilename) {
        PerWalletModelData perWalletModelDataToReturn = null;

        File walletFile = null;
        boolean walletFileIsADirectory = false;
        boolean newWalletCreated = false;

        if (walletFilename != null) {
            walletFile = new File(walletFilename);
            if (walletFile.isDirectory()) {
                walletFileIsADirectory = true;
            } else {
                try {
                    perWalletModelDataToReturn = controller.getFileHandler().loadFromFile(walletFile);
                    if (perWalletModelDataToReturn != null) {
                        wallet = perWalletModelDataToReturn.getWallet();
                    }
                } catch (IOException ioe) {
                    // TODO need to report back to user or will create a new
                    // wallet
                }
            }
        }

        if (wallet == null || walletFilename == null || walletFilename.equals("") || walletFileIsADirectory) {
            // use default wallet name - create if does not exist
            if ("".equals(controller.getApplicationDataDirectoryLocator().getApplicationDataDirectory())) {
                walletFilename = getFilePrefix() + WALLET_SUFFIX;
            } else {
                walletFilename = controller.getApplicationDataDirectoryLocator().getApplicationDataDirectory() + File.separator
                        + getFilePrefix() + WALLET_SUFFIX;
            }

            walletFile = new File(walletFilename);

            if (walletFile.exists()) {
                // wallet file exists with default name
                try {
                    perWalletModelDataToReturn = controller.getFileHandler().loadFromFile(walletFile);
                    if (perWalletModelDataToReturn != null) {
                        wallet = perWalletModelDataToReturn.getWallet();
                    }

                    newWalletCreated = true;
                } catch (IOException e) {
                    e.printStackTrace();
                }
            } else {
                // create a brand new wallet
                wallet = new Wallet(networkParameters);
                ECKey newKey = new ECKey();
                wallet.keychain.add(newKey);

                perWalletModelDataToReturn = controller.getModel().addWallet(wallet, walletFile.getAbsolutePath());

                // create a wallet info
                WalletInfo walletInfo = new WalletInfo(walletFile.getAbsolutePath());
                perWalletModelDataToReturn.setWalletInfo(walletInfo);

                // set a default description
                String defaultDescription = controller.getLocaliser().getString("createNewWalletSubmitAction.defaultDescription");
                perWalletModelDataToReturn.setWalletDescription(defaultDescription);

                controller.getFileHandler().savePerWalletModelData(perWalletModelDataToReturn, true);

                newWalletCreated = true;
            }
        }

        if (wallet != null) {
            // add the keys for this wallet to the address book as receiving
            // addresses
            ArrayList<ECKey> keys = wallet.keychain;
            if (keys != null) {
                if (!newWalletCreated) {
                    perWalletModelDataToReturn = controller.getModel().getPerWalletModelDataByWalletFilename(walletFilename);
                }
                if (perWalletModelDataToReturn != null) {
                    WalletInfo walletInfo = perWalletModelDataToReturn.getWalletInfo();
                    if (walletInfo != null) {
                        for (ECKey key : keys) {
                            if (key != null) {
                                Address address = key.toAddress(networkParameters);
                                walletInfo.addReceivingAddressOfKey(address);
                            }
                        }
                    }
                }
            }

            // add wallet to blockchain
            blockChain.addWallet(wallet);

            // add wallet to PeerGroup
            peerGroup.addWallet(wallet);

            if (newWalletCreated) {
                controller.fireNewWalletCreated();
            }
        }

        return perWalletModelDataToReturn;
    }

    /**
     * replay blockchain
     * 
     * @param dateToReplayFrom
     *            the date on the blockchain to replay from - if missing replay
     *            from genesis block
     */
    public void replayBlockChain(Date dateToReplayFrom) throws BlockStoreException {
        // navigate backwards in the blockchain to work out how far back in
        // time to go

        if (dateToReplayFrom == null) {
            // create empty new block chain
            blockStore = new ReplayableBlockStore(networkParameters, new File(blockchainFilename), true);

            log.debug("Creating new blockStore - need to redownload from Genesis block");
            blockChain = new BlockChain(networkParameters, (BlockStore) blockStore);

            // TODO Peers need to be updated with the new block chain
        } else {
            StoredBlock storedBlock = blockChain.getChainHead();

            boolean haveGoneBackInTimeEnough = false;
            int numberOfBlocksGoneBackward = 0;

            while (!haveGoneBackInTimeEnough) {
                Block header = storedBlock.getHeader();
                long headerTimeInSeconds = header.getTimeSeconds();
                if (headerTimeInSeconds < (dateToReplayFrom.getTime() / NUMBER_OF_MILLISECOND_IN_A_SECOND)) {
                    haveGoneBackInTimeEnough = true;
                } else {
                    try {
                        storedBlock = storedBlock.getPrev(blockStore);
                        numberOfBlocksGoneBackward++;
                    } catch (BlockStoreException e) {
                        e.printStackTrace();
                        // we have to stop - fail
                        break;
                    }
                }
            }

            // in case the chain head was on an alternate fork go back more
            // blocks to ensure
            // back on the main chain
            while (numberOfBlocksGoneBackward < MAXIMUM_EXPECTED_LENGTH_OF_ALTERNATE_CHAIN) {
                try {
                    storedBlock = storedBlock.getPrev(blockStore);
                    numberOfBlocksGoneBackward++;
                } catch (BlockStoreException e) {
                    e.printStackTrace();
                    // we have to stop - fail
                    break;
                }
            }

            // set the block chain head to the block just before the
            // earliest transaction in the wallet
            blockChain.setChainHead(storedBlock);

            // clear any cached data in the BlockStore
            blockStore.clearCaches();
        }

        downloadBlockChain();
    }

    /**
     * download the block chain
     */
    public void downloadBlockChain() {
        @SuppressWarnings("rawtypes")
        SwingWorker worker = new SwingWorker() {
            @Override
            protected Object doInBackground() throws Exception {
                peerGroup.downloadBlockChain();
                return null; // return not used
            }
        };
        worker.execute();
    }

    /**
     * send bitcoins from the active wallet
     * 
     * @param sendAddressString
     *            the address to send to, as a String
     * @param fee
     *            fee to pay in nanocoin
     * @param amount
     *            the amount to send to, in BTC, as a String
     */

    public Transaction sendCoins(PerWalletModelData perWalletModelData, String sendAddressString, String amount, BigInteger fee)
            throws java.io.IOException, AddressFormatException {
        // send the coins
        Address sendAddress = new Address(networkParameters, sendAddressString);

        Transaction sendTransaction = perWalletModelData.getWallet().sendCoins(peerGroup, sendAddress, Utils.toNanoCoins(amount),
                fee);
        controller.getMultiBitService().getPeerGroup().broadcastTransaction(sendTransaction);

        assert sendTransaction != null; // We should never try to send more
        // coins than we have!
        // throw an exception if sendTransaction is null - no money
        if (sendTransaction != null) {
            log.debug("MultiBitService#sendCoins - Sent coins. Transaction hash is {}", sendTransaction.getHashAsString());

            controller.getFileHandler().savePerWalletModelData(perWalletModelData, false);

            // notify all of the pendingTransactionsListeners about the new
            // transaction
            // peerGroup.processPendingTransaction(sendTransaction);
        } else {
            // transaction was null
        }
        return sendTransaction;
    }

    public PeerGroup getPeerGroup() {
        return peerGroup;
    }

    public BlockChain getChain() {
        return blockChain;
    }

    public NetworkParameters getNetworkParameters() {
        return networkParameters;
    }
}
