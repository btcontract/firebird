package com.btcontract.wallet.steps

import com.btcontract.wallet.ln.crypto.Tools._
import com.btcontract.wallet.{ExternalDataChecker, R, FirebirdActivity, WalletApp}
import android.widget.{AbsListView, ArrayAdapter, LinearLayout, ListView}
import ernestoyaquello.com.verticalstepperform.Step.IsDataValid
import com.btcontract.wallet.helper.OnListItemClickListener
import ernestoyaquello.com.verticalstepperform.Step
import fr.acinq.eclair.wire.NodeAnnouncement
import com.btcontract.wallet.ln.SyncMaster
import com.ornach.nobobutton.NoboButton
import android.util.SparseBooleanArray
import android.view.View


case class Providers(list: List[NodeAnnouncement] = Nil)
class ChooseProviders(host: FirebirdActivity, title: String) extends Step[Providers](title, true) with ExternalDataChecker { me =>
  val availableProviders: Array[NodeAnnouncement] = SyncMaster.hostedChanNodes.toArray
  var chosenProviders: Providers = Providers(Nil)
  var list: ListView = _

  private[this] val lisetner = new OnListItemClickListener {
    override def onItemClicked(selectedPosition: Int): Unit = {
      val checkedPositions: SparseBooleanArray = list.getCheckedItemPositions
      val anns = availableProviders.indices.filter(checkedPositions.get).map(availableProviders)
      chosenProviders = Providers(anns.toList)
      markAsCompletedOrUncompleted(true)
    }
  }

  override def checkExternalData: Unit =
    WalletApp checkAndMaybeErase {
      case ann: NodeAnnouncement =>
        chosenProviders = Providers(ann :: Nil)
        markAsCompletedOrUncompleted(true)
        getFormView.goToNextStep(true)
        list.clearChoices

      case _ =>
        val msg = R.string.err_nothing_useful
        WalletApp.app.quickToast(msg)
    }

  override def createStepContentLayout: View = {
    val view = host.getLayoutInflater.inflate(R.layout.frag_step_choose, null).asInstanceOf[LinearLayout]
    val adapter = new ArrayAdapter(host, R.layout.multi_choice_item_left, for (provider <- availableProviders) yield provider.alias)
    val scanNodeQrCode = view.findViewById(R.id.scanNodeQrCode).asInstanceOf[NoboButton]
    scanNodeQrCode setOnClickListener host.onButtonTap(host callScanner me)

    list = view.findViewById(R.id.list).asInstanceOf[ListView]
    list.setChoiceMode(AbsListView.CHOICE_MODE_MULTIPLE)
    list.setOnItemClickListener(lisetner)
    list.setDividerHeight(0)
    list.setAdapter(adapter)
    view
  }

  override def getStepData: Providers = chosenProviders
  override def getStepDataAsHumanReadableString: String = getStepData.list.map(_.alias).mkString(", ")
  override def isStepDataValid(stepData: Providers): IsDataValid = new IsDataValid(stepData.list.nonEmpty, new String)

  override def onStepOpened(animated: Boolean): Unit =
    if (chosenProviders.list.isEmpty) {
      list.setItemChecked(0, true)
      lisetner.onItemClicked(0)
    }

  override def onStepClosed(animated: Boolean): Unit = none
  override def onStepMarkedAsCompleted(animated: Boolean): Unit = none
  override def onStepMarkedAsUncompleted(animated: Boolean): Unit = none
  override def restoreStepData(stepData: Providers): Unit = none
}
