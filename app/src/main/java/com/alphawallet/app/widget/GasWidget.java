package com.alphawallet.app.widget;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.alphawallet.app.C;
import com.alphawallet.app.R;
import com.alphawallet.app.entity.GasPriceSpread;
import com.alphawallet.app.entity.StandardFunctionInterface;
import com.alphawallet.app.entity.tokens.Token;
import com.alphawallet.app.repository.TokensRealmSource;
import com.alphawallet.app.repository.entity.RealmGasSpread;
import com.alphawallet.app.repository.entity.RealmTokenTicker;
import com.alphawallet.app.service.GasService2;
import com.alphawallet.app.service.TickerService;
import com.alphawallet.app.service.TokensService;
import com.alphawallet.app.ui.GasSettingsActivity;
import com.alphawallet.app.ui.widget.entity.GasSpeed;
import com.alphawallet.app.util.BalanceUtils;
import com.alphawallet.app.util.Utils;
import com.alphawallet.app.web3.entity.Web3Transaction;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import io.realm.Realm;
import io.realm.RealmQuery;
import io.realm.Sort;

import static com.alphawallet.app.C.DEFAULT_GAS_LIMIT_FOR_END_CONTRACT;
import static com.alphawallet.app.C.DEFAULT_GAS_PRICE;
import static com.alphawallet.app.C.DEFAULT_UNKNOWN_FUNCTION_GAS_LIMIT;
import static com.alphawallet.app.C.GAS_LIMIT_MIN;
import static com.alphawallet.app.repository.EthereumNetworkBase.MAINNET_ID;

/**
 * Created by JB on 19/11/2020.
 */
public class GasWidget extends LinearLayout implements Runnable
{
    private RealmGasSpread realmGasSpread;
    private TokensService tokensService;
    private BigInteger customGasLimit;    //from slider
    private BigInteger initialTxGasLimit; //this is the gas limit specified from the dapp transaction.
    private BigInteger baseLineGasLimit;  //this is our candidate gas limit - either from the dapp or a default, can be replaced by accurate estimate if initialTx was zero
    private BigInteger presetGasLimit;    //this is the gas limit used for the presets. It will use, in order of priority: gas estimate from node, gas from dapp tx, calculated gas
    private BigInteger transactionValue;  //'value' base token amount from dapp transaction
    private BigInteger adjustedValue;     //adjusted value, in case we are use 'all funds' to wipe an account.
    private BigInteger initialGasPrice;   //gasprice from dapp transaction
    private Token token;
    private Activity baseActivity;
    private StandardFunctionInterface functionInterface;

    private final Handler handler = new Handler();

    private final TextView speedText;
    private final TextView timeEstimate;
    private final LinearLayout gasWarning;
    private final Context context;

    private final List<GasSpeed> gasSpeeds;
    private int currentGasSpeedIndex = -1;
    private int customGasSpeedIndex = 0;
    private long customNonce = -1;
    private boolean isSendingAll;
    private boolean forceCustomGas;

    public GasWidget(Context ctx, AttributeSet attrs)
    {
        super(ctx, attrs);
        inflate(ctx, R.layout.item_gas_settings, this);

        context = ctx;
        speedText = findViewById(R.id.text_speed);
        timeEstimate = findViewById(R.id.text_time_estimate);
        gasWarning = findViewById(R.id.layout_gas_warning);

        gasSpeeds = new ArrayList<>();

        setOnClickListener(v -> {
            if (gasSpeeds.size() == 0) return;
            Token baseEth = tokensService.getToken(token.tokenInfo.chainId, token.getWallet());
            Intent intent = new Intent(context, GasSettingsActivity.class);
            intent.putExtra(C.EXTRA_SINGLE_ITEM, currentGasSpeedIndex);
            intent.putExtra(C.EXTRA_CHAIN_ID, token.tokenInfo.chainId);
            intent.putExtra(C.EXTRA_GAS_LIMIT, baseLineGasLimit.toString());
            intent.putExtra(C.EXTRA_CUSTOM_GAS_LIMIT, customGasLimit.toString());
            intent.putExtra(C.EXTRA_GAS_LIMIT_PRESET, presetGasLimit.toString());
            intent.putExtra(C.EXTRA_TOKEN_BALANCE, baseEth.balance.toString());
            intent.putExtra(C.EXTRA_AMOUNT, transactionValue.toString());
            intent.putExtra(C.EXTRA_GAS_PRICE, gasSpeeds.get(customGasSpeedIndex).gasPrice.toString());
            intent.putExtra(C.EXTRA_NONCE, customNonce);
            baseActivity.startActivityForResult(intent, C.SET_GAS_SETTINGS);
        });
    }

    public void setupWidget(TokensService svs, Token t, Web3Transaction tx, StandardFunctionInterface sfi, Activity act)
    {
        tokensService = svs;
        token = t;
        initialTxGasLimit = tx.gasLimit;
        baseActivity = act;
        functionInterface = sfi;
        transactionValue = tx.value;
        adjustedValue = tx.value;
        isSendingAll = isSendingAll(tx);
        initialGasPrice = tx.gasPrice;

        if (tx.gasLimit.equals(BigInteger.ZERO)) //dapp didn't specify a limit, use default limits until node returns an estimate (see setGasEstimate())
        {
            baseLineGasLimit = GasService2.getDefaultGasLimit(token, tx);
        }
        else
        {
            baseLineGasLimit = tx.gasLimit;
        }

        presetGasLimit = baseLineGasLimit;
        customGasLimit = baseLineGasLimit;

        setupGasSpeeds(tx.gasPrice);
        startGasListener();
    }

    private void setupGasSpeeds(BigInteger priceFromTx)
    {
        if (priceFromTx.compareTo(BigInteger.ZERO) > 0)
        {
            gasSpeeds.add(buildCustomGasElement(priceFromTx));
            forceCustomGas = true;
        }
        else
        {
            priceFromTx = new BigInteger(DEFAULT_GAS_PRICE);
        }

        RealmGasSpread getGas = getGasQuery().findFirst();
        if (getGas != null)
        {
            initGasSpeeds(getGas.getGasPrice());
        }
        else
        {
            // Couldn't get current gas. Add a blank custom gas speed node
            gasSpeeds.add(buildCustomGasElement(priceFromTx));
            forceCustomGas = true;
        }
    }

    private GasSpeed buildCustomGasElement(BigInteger newGasPrice)
    {
        String customSpeedTitle;
        if (initialTxGasLimit.compareTo(BigInteger.ZERO) > 0 && initialGasPrice.compareTo(BigInteger.ZERO) > 0
            && customGasLimit.equals(initialTxGasLimit) && newGasPrice.equals(initialGasPrice))
        {
            customSpeedTitle = getContext().getString(R.string.speed_dapp);
        }
        else
        {
            customSpeedTitle = getContext().getString(R.string.speed_custom);
        }

        return new GasSpeed(customSpeedTitle, GasPriceSpread.FAST_SECONDS, newGasPrice, true);
    }

    public void onDestroy()
    {
        if (realmGasSpread != null) realmGasSpread.removeAllChangeListeners();
    }

    /**
     * This function is the leaf for when the user clicks on a gas setting; fast, slow, custom, etc
     *
     * @param gasSelectionIndex
     * @param customGasPrice
     * @param customGasLimit
     * @param expectedTxTime
     * @param nonce
     */
    public void setCurrentGasIndex(int gasSelectionIndex, BigDecimal customGasPrice, BigDecimal customGasLimit, long expectedTxTime, long nonce)
    {
        currentGasSpeedIndex = gasSelectionIndex;
        customNonce = nonce;
        handleCustomGas(customGasPrice, customGasLimit, expectedTxTime);
        handler.post(this);
    }

    private void handleCustomGas(BigDecimal customGasPrice, BigDecimal custGasLimit, long expectedTxTime)
    {
        GasSpeed gs = gasSpeeds.get(currentGasSpeedIndex);
        if (gs.isCustom)
        {
            gs = buildCustomGasElement(customGasPrice.toBigInteger());
            gasSpeeds.remove(currentGasSpeedIndex);
            gasSpeeds.add(gs);
            customGasLimit = custGasLimit.toBigInteger();
        }

        adjustedValue = calculateSendAllValue();
        tokensService.track(gs.speed);
    }

    public boolean checkSufficientGas()
    {
        BigInteger useGasLimit = getUseGasLimit();
        boolean sufficientGas = true;
        GasSpeed gs = gasSpeeds.get(currentGasSpeedIndex);
        BigDecimal networkFee = new BigDecimal(gs.gasPrice.multiply(useGasLimit));
        Token base = tokensService.getToken(token.tokenInfo.chainId, token.getWallet());

        if (isSendingAll)
        {
            sufficientGas = token.balance.subtract(new BigDecimal(adjustedValue).add(networkFee)).compareTo(BigDecimal.ZERO) >= 0;
        }
        else if (token.isEthereum() && token.balance.subtract(new BigDecimal(transactionValue).add(networkFee)).compareTo(BigDecimal.ZERO) < 0)
        {
            sufficientGas = false;
        }
        else if (!token.isEthereum() && base.balance.subtract(networkFee).compareTo(BigDecimal.ZERO) < 0)
        {
            sufficientGas = false;
        }

        if (!sufficientGas)
        {
            gasWarning.setVisibility(View.VISIBLE);
            speedText.setVisibility(View.GONE);
        }
        else
        {
            gasWarning.setVisibility(View.GONE);
            speedText.setVisibility(View.VISIBLE);
        }

        return sufficientGas;
    }

    private BigInteger getUseGasLimit()
    {
        if (currentGasSpeedIndex == customGasSpeedIndex)
        {
            return customGasLimit;
        }
        else
        {
            return presetGasLimit;
        }
    }

    private BigInteger calculateSendAllValue()
    {
        BigInteger sendAllValue;
        GasSpeed gs = gasSpeeds.get(currentGasSpeedIndex);
        BigDecimal networkFee = new BigDecimal(gs.gasPrice.multiply(getUseGasLimit()));

        if (isSendingAll)
        {
            //need to recalculate the 'send all' value
            //calculate max amount possible
            sendAllValue = token.balance.subtract(networkFee).toBigInteger();
            if (sendAllValue.compareTo(BigInteger.ZERO) < 0) sendAllValue = BigInteger.ZERO;
        }
        else
        {
            sendAllValue = transactionValue;
        }

        return sendAllValue;
    }

    private RealmQuery<RealmGasSpread> getGasQuery()
    {
        return tokensService.getTickerRealmInstance().where(RealmGasSpread.class)
                .equalTo("chainId", token.tokenInfo.chainId)
                .sort("timeStamp", Sort.DESCENDING);
    }

    private void startGasListener()
    {
        realmGasSpread = getGasQuery().findFirstAsync();
        realmGasSpread.addChangeListener(realmToken -> {
            if (realmGasSpread.isValid())
            {
                initGasSpeeds(((RealmGasSpread) realmToken).getGasPrice());
            }
        });
    }

    private void initGasSpeeds(GasPriceSpread gs)
    {
        try
        {
            currentGasSpeedIndex = gs.setupGasSpeeds(context, gasSpeeds, currentGasSpeedIndex);
            customGasSpeedIndex = gs.getCustomIndex();
            if (forceCustomGas)
            {
                currentGasSpeedIndex = customGasSpeedIndex;
                forceCustomGas = false;
            }
            //if we have mainnet then show timings, otherwise no timing, if the token has fiat value, show fiat value of gas, so we need the ticker
            handler.post(this);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    /**
     * Update the UI with the gas value and expected transaction time (if main net).
     * Note - there is no ticker listener - it's unlikely any ticker change would produce a noticeable change in the gas price
     */
    @Override
    public void run()
    {
        if (currentGasSpeedIndex == -1) currentGasSpeedIndex = 0;
        GasSpeed gs = gasSpeeds.get(currentGasSpeedIndex);

        Token baseCurrency = tokensService.getToken(token.tokenInfo.chainId, token.getWallet());
        BigInteger networkFee = gs.gasPrice.multiply(getUseGasLimit());
        String gasAmountInBase = BalanceUtils.getScaledValueScientific(new BigDecimal(networkFee), baseCurrency.tokenInfo.decimals);
        if (gasAmountInBase.equals("0")) gasAmountInBase = "0.0001";
        String displayStr = context.getString(R.string.gas_amount, gasAmountInBase, baseCurrency.getSymbol());

        //Can we display value for gas?
        try (Realm realm = tokensService.getTickerRealmInstance())
        {
            RealmTokenTicker rtt = realm.where(RealmTokenTicker.class)
                    .equalTo("contract", TokensRealmSource.databaseKey(token.tokenInfo.chainId, "eth"))
                    .findFirst();

            if (rtt != null)
            {
                //calculate equivalent fiat
                double cryptoRate = Double.parseDouble(rtt.getPrice());
                double cryptoAmount = BalanceUtils.weiToEth(new BigDecimal(networkFee)).doubleValue();//Double.parseDouble(gasAmountInBase);
                displayStr += context.getString(R.string.gas_fiat_suffix,
                        TickerService.getCurrencyString(cryptoAmount * cryptoRate),
                        rtt.getCurrencySymbol());

                if (token.tokenInfo.chainId == MAINNET_ID && gs.seconds > 0)
                {
                    displayStr += context.getString(R.string.gas_time_suffix,
                            Utils.shortConvertTimePeriodInSeconds(gs.seconds, context));
                }
            }
        }
        catch (Exception e)
        {
            //
        }

        timeEstimate.setText(displayStr);
        speedText.setText(gs.speed);
        adjustedValue = calculateSendAllValue();

        if (isSendingAll)
        {
            functionInterface.updateAmount();
        }

        checkSufficientGas();
    }

    public BigInteger getGasPrice(BigInteger defaultPrice)
    {
        if (currentGasSpeedIndex == -1)
        {
            return defaultPrice;
        }
        else
        {
            GasSpeed gs = gasSpeeds.get(currentGasSpeedIndex);
            return gs.gasPrice;
        }
    }

    public BigInteger getValue()
    {
        if (isSendingAll)
        {
            return adjustedValue;
        }
        else
        {
            return transactionValue;
        }
    }

    public BigInteger getGasLimit()
    {
        return getUseGasLimit();
    }

    public long getNonce()
    {
        if (currentGasSpeedIndex == customGasSpeedIndex)
        {
            return customNonce;
        }
        else
        {
            return -1;
        }
    }

    public long getExpectedTransactionTime()
    {
        GasSpeed gs = gasSpeeds.get(currentGasSpeedIndex);
        return gs.seconds;
    }

    private boolean isSendingAll(Web3Transaction tx)
    {
        if (token.isEthereum())
        {
            //gas fee:
            BigDecimal networkFee = new BigDecimal(tx.gasPrice.multiply(BigInteger.valueOf(GAS_LIMIT_MIN)));
            BigDecimal remainder = token.balance.subtract(new BigDecimal(tx.value).add(networkFee));
            return remainder.equals(BigDecimal.ZERO);
        }

        return false;
    }

    /**
     * Node eth_gasEstimate returned a transaction estimate
     *
     * @param estimate
     */
    public void setGasEstimate(BigInteger estimate)
    {
        if (initialTxGasLimit.equals(BigInteger.ZERO))
        {
            baseLineGasLimit = estimate;
        }

        //presets always use estimate if available
        presetGasLimit = estimate;

        //now update speeds
        handler.post(this);
    }
}
