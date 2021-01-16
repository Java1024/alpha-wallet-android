package com.alphawallet.app.widget;

import android.app.Activity;
import android.content.Intent;
import android.text.TextUtils;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.alphawallet.app.C;
import com.alphawallet.app.R;
import com.alphawallet.app.entity.ActionSheetInterface;
import com.alphawallet.app.entity.SignAuthenticationCallback;
import com.alphawallet.app.entity.StandardFunctionInterface;
import com.alphawallet.app.entity.tokens.Token;
import com.alphawallet.app.repository.TokensRealmSource;
import com.alphawallet.app.repository.entity.RealmTokenTicker;
import com.alphawallet.app.repository.entity.RealmTransaction;
import com.alphawallet.app.service.TickerService;
import com.alphawallet.app.service.TokensService;
import com.alphawallet.app.ui.TransactionSuccessActivity;
import com.alphawallet.app.ui.widget.entity.ActionSheetCallback;
import com.alphawallet.app.util.BalanceUtils;
import com.alphawallet.app.util.Utils;
import com.alphawallet.app.web3.entity.Address;
import com.alphawallet.app.web3.entity.Web3Transaction;
import com.alphawallet.token.entity.Signable;
import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;

import io.realm.Realm;

import static com.alphawallet.app.repository.EthereumNetworkBase.MAINNET_ID;

/**
 * Created by JB on 17/11/2020.
 */
public class ActionSheetDialog extends BottomSheetDialog implements StandardFunctionInterface, ActionSheetInterface
{
    private final TextView balance;
    private final TextView newBalance;
    private final TextView amount;

    private final ImageView cancelButton;
    private final GasWidget gasWidget;
    private final ConfirmationWidget confirmationWidget;
    private final ChainName chainName;
    private final AddressDetailView addressDetail;
    private final FunctionButtonBar functionBar;
    private final TransactionDetailWidget detailWidget;

    private final Token token;
    private final TokensService tokensService;

    private final Web3Transaction candidateTransaction;
    private final ActionSheetCallback actionSheetCallback;
    private final SignAuthenticationCallback signCallback;
    private final ActionSheetMode mode;

    private String txHash = null;

    public ActionSheetDialog(@NonNull Activity activity, Web3Transaction tx, Token t,
                             String destName, TokensService ts, ActionSheetCallback aCallBack)
    {
        super(activity);
        setContentView(R.layout.dialog_action_sheet);

        balance = findViewById(R.id.text_balance);
        newBalance = findViewById(R.id.text_new_balance);
        amount = findViewById(R.id.text_amount);

        gasWidget = findViewById(R.id.gas_widgetx);
        cancelButton = findViewById(R.id.image_close);
        chainName = findViewById(R.id.chain_name);
        confirmationWidget = findViewById(R.id.confirmation_view);
        detailWidget = findViewById(R.id.detail_widget);
        addressDetail = findViewById(R.id.recipient);
        functionBar = findViewById(R.id.layoutButtons);
        mode = ActionSheetMode.SEND_TRANSACTION;
        signCallback = null;

        actionSheetCallback = aCallBack;

        token = t;
        tokensService = ts;
        candidateTransaction = tx;

        balance.setText(activity.getString(R.string.total_cost, token.getStringBalance(), token.getSymbol()));

        functionBar.setupFunctions(this, new ArrayList<>(Collections.singletonList(R.string.action_confirm)));
        functionBar.revealButtons();

        gasWidget.setupWidget(ts, token, candidateTransaction, this, activity);
        updateAmount();

        if (token.tokenInfo.chainId == MAINNET_ID)
        {
            chainName.setVisibility(View.GONE);
        }
        else
        {
            chainName.setVisibility(View.VISIBLE);
            chainName.setChainID(token.tokenInfo.chainId);
        }

        addressDetail.setupAddress(tx.recipient.toString(), destName);

        cancelButton.setOnClickListener(v -> {
           dismiss();
        });

        setOnDismissListener(v -> {
            actionSheetCallback.dismissed(txHash);
        });
    }

    public void setURL(String url)
    {
        AddressDetailView requester = findViewById(R.id.requester);
        requester.setupRequester(url);
        detailWidget.setupTransaction(candidateTransaction, token.tokenInfo.chainId, tokensService.getCurrentAddress(),
                tokensService.getNetworkSymbol(token.tokenInfo.chainId));
        if (candidateTransaction.isConstructor())
        {
            addressDetail.setVisibility(View.GONE);
        }

        detailWidget.setLockCallback(this);
    }

    public ActionSheetDialog(@NonNull Fragment fragment, Signable message)
    {
        super(fragment.getActivity());
        setContentView(R.layout.dialog_action_sheet_sign);

        gasWidget = findViewById(R.id.gas_widgetx);
        cancelButton = findViewById(R.id.image_close);
        chainName = findViewById(R.id.chain_name);
        confirmationWidget = findViewById(R.id.confirmation_view);
        addressDetail = findViewById(R.id.requester);
        functionBar = findViewById(R.id.layoutButtons);
        balance = null;
        newBalance = null;
        amount = null;
        detailWidget = null;
        mode = ActionSheetMode.SIGN_MESSAGE;

        actionSheetCallback = (ActionSheetCallback) fragment;
        signCallback = (SignAuthenticationCallback) fragment;

        token = null;
        tokensService = null;
        candidateTransaction = null;

        addressDetail.setupRequester(message.getOrigin());
        SignDataWidget signWidget = findViewById(R.id.sign_widget);
        signWidget.setupSignData(message);
        signWidget.setLockCallback(this);

        TextView signTitle = findViewById(R.id.text_sign_title);
        signTitle.setText(Utils.getSigningTitle(message));

        functionBar.setupFunctions(this, new ArrayList<>(Collections.singletonList(R.string.action_confirm)));
        functionBar.revealButtons();
    }

    public void onDestroy()
    {
        gasWidget.onDestroy();
    }

    public void setCurrentGasIndex(int gasSelectionIndex, BigDecimal customGasPrice, BigDecimal customGasLimit, long expectedTxTime, long nonce)
    {
        gasWidget.setCurrentGasIndex(gasSelectionIndex, customGasPrice, customGasLimit, expectedTxTime, nonce);
        updateAmount();
    }

    private void setNewBalanceText()
    {
        BigInteger networkFee = gasWidget.getGasPrice(candidateTransaction.gasPrice).multiply(gasWidget.getGasLimit());
        BigInteger balanceAfterTransaction = token.balance.toBigInteger().subtract(gasWidget.getValue());
        if (token.isEthereum())
        {
            balanceAfterTransaction = balanceAfterTransaction.subtract(networkFee).max(BigInteger.ZERO);
        }
        //convert to ETH amount
        String newBalanceVal = BalanceUtils.getScaledValueScientific(new BigDecimal(balanceAfterTransaction), token.tokenInfo.decimals);
        newBalance.setText(getContext().getString(R.string.new_balance, newBalanceVal, token.getSymbol()));
    }

    @Override
    public void updateAmount()
    {
        String amountVal = BalanceUtils.getScaledValueScientific(new BigDecimal(gasWidget.getValue()), token.tokenInfo.decimals);
        showAmount(amountVal);
    }

    @Override
    public void handleClick(String action, int id)
    {
        switch (mode)
        {
            case SEND_TRANSACTION:
                //check gas and warn user
                if (!gasWidget.checkSufficientGas())
                {
                    askUserForInsufficientGasConfirm();
                }
                else
                {
                    sendTransaction();
                }
                break;
            case SIGN_MESSAGE:
                signMessage();
                break;
        }
    }

    private void signMessage()
    {
        //get authentication
        functionBar.setVisibility(View.GONE);

        //authentication screen
        SignAuthenticationCallback localSignCallback = new SignAuthenticationCallback()
        {
            @Override
            public void gotAuthorisation(boolean gotAuth)
            {
                //display success and hand back to calling function
                confirmationWidget.startProgressCycle(1);
                signCallback.gotAuthorisation(gotAuth);
            }

            @Override
            public void cancelAuthentication()
            {
                confirmationWidget.hide();
                signCallback.gotAuthorisation(false);
            }
        };

        actionSheetCallback.getAuthorisation(localSignCallback);
    }

    /**
     * Popup a dialogbox to ask user if they really want to try to send this transaction,
     * as we calculate it will fail due to insufficient gas. User knows best though.
     */
    private void askUserForInsufficientGasConfirm()
    {
        AWalletAlertDialog dialog = new AWalletAlertDialog(getContext());
        dialog.setIcon(AWalletAlertDialog.WARNING);
        dialog.setTitle(R.string.insufficient_gas);
        dialog.setMessage(getContext().getString(R.string.not_enough_gas_message));
        dialog.setButtonText(R.string.action_send);
        dialog.setSecondaryButtonText(R.string.cancel_transaction);
        dialog.setButtonListener(v -> {
            dialog.dismiss();
            sendTransaction();
        });
        dialog.setSecondaryButtonListener(v -> {
            dialog.dismiss();
        });
        dialog.show();
    }

    public void transactionWritten(String tx)
    {
        txHash = tx;
        //dismiss on message completion
        confirmationWidget.completeProgressMessage(txHash, this::showTransactionSuccess);
        if (!TextUtils.isEmpty(tx))
        {
            updateRealmTransactionFinishEstimate(tx);
        }
    }

    private void showTransactionSuccess()
    {
        Intent intent = new Intent(getContext(), TransactionSuccessActivity.class);
        intent.putExtra(C.EXTRA_TXHASH, txHash);
        intent.setFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        getContext().startActivity(intent);
        dismiss();
    }

    private void updateRealmTransactionFinishEstimate(String txHash)
    {
        try (Realm realm = tokensService.getWalletRealmInstance())
        {
            RealmTransaction rt = realm.where(RealmTransaction.class)
                    .equalTo("hash", txHash)
                    .findFirst();

            if (rt != null)
            {
                realm.executeTransaction(instance -> {
                    rt.setExpectedCompletion(System.currentTimeMillis() + gasWidget.getExpectedTransactionTime() * 1000);
                });
            }
        }
    }

    private void sendTransaction()
    {
        functionBar.setVisibility(View.GONE);
        //form Web3Transaction
        //get user gas settings
        final Web3Transaction finalTx = new Web3Transaction(
                candidateTransaction.recipient,
                candidateTransaction.contract,
                gasWidget.getValue(),
                gasWidget.getGasPrice(candidateTransaction.gasPrice),
                gasWidget.getGasLimit(),
                gasWidget.getNonce(),
                candidateTransaction.payload,
                candidateTransaction.leafPosition
        );

        //get approval and push transaction

        //authentication screen
        SignAuthenticationCallback signCallback = new SignAuthenticationCallback()
        {
            @Override
            public void gotAuthorisation(boolean gotAuth)
            {
                confirmationWidget.startProgressCycle(4);
                //send the transaction
                actionSheetCallback.sendTransaction(finalTx);
            }

            @Override
            public void cancelAuthentication()
            {
                confirmationWidget.hide();
                functionBar.setVisibility(View.VISIBLE);
            }
        };

        actionSheetCallback.getAuthorisation(signCallback);
    }

    @Override
    public void lockDragging(boolean lock)
    {
        getBehavior().setDraggable(!lock);

        //ensure view fully expanded when locking scroll. Otherwise we may not be able to see our expanded view
        if (lock)
        {
            FrameLayout bottomSheet = findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) BottomSheetBehavior.from(bottomSheet).setState(BottomSheetBehavior.STATE_EXPANDED);
        }
    }

    public void setGasEstimate(BigInteger estimate)
    {
        String amountVal = BalanceUtils.getScaledValueScientific(new BigDecimal(candidateTransaction.value.add(estimate)), token.tokenInfo.decimals);
        //TODO: Show gas estimate in widget
        //showAmount(amountVal);
    }

    private void showAmount(String amountVal)
    {
        String displayStr = getContext().getString(R.string.total_cost, amountVal, token.getSymbol());

        //fetch ticker if required
        if (gasWidget.getValue().compareTo(BigInteger.ZERO) > 0)
        {
            try (Realm realm = tokensService.getTickerRealmInstance())
            {
                RealmTokenTicker rtt = realm.where(RealmTokenTicker.class)
                        .equalTo("contract", TokensRealmSource.databaseKey(token.tokenInfo.chainId, token.isEthereum() ? "eth" : token.getAddress().toLowerCase()))
                        .findFirst();

                if (rtt != null)
                {
                    //calculate equivalent fiat
                    double cryptoRate = Double.parseDouble(rtt.getPrice());
                    double cryptoAmount = Double.parseDouble(amountVal);
                    displayStr = getContext().getString(R.string.fiat_format, amountVal, token.getSymbol(),
                            TickerService.getCurrencyString(cryptoAmount * cryptoRate),
                            rtt.getCurrencySymbol()) ;
                }
            }
            catch (Exception e)
            {
                //
            }
        }

        amount.setText(displayStr);
        setNewBalanceText();
    }
}