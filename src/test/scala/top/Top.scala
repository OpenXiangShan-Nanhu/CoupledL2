package top

import chisel3._
import chisel3.stage.ChiselGeneratorAnnotation
import chisel3.util.Decoupled
import circt.stage.FirtoolOption
import coupledL2.tl2chi.{CHIIssue, DecoupledCHI, Issue, TL2CHICoupledL2}
import freechips.rocketchip.diplomacy.{IdRange, TransferSizes}
import freechips.rocketchip.tile.MaxHartIdBits
import freechips.rocketchip.tilelink.{BankBinder, TLBuffer, TLChannelBeatBytes, TLClientNode, TLMasterParameters, TLMasterPortParameters, TLXbar}
import org.chipsalliance.diplomacy.bundlebridge.BundleBridgeSource
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}
import org.chipsalliance.diplomacy.DisableMonitors
import org.chipsalliance.cde.config.{Config, Parameters}
import xs.utils.cache.common.{AliasField, BankBitsKey, L2ParamKey, PrefetchField, VaddrField}
import coupledL2.prefetch.PrefetchReceiverParams
import xs.utils.{DFTResetSignals, FileRegisters}
import xs.utils.cache.{EnableCHI, L1Param, L2Param}
import xs.utils.debug.{HardwareAssertion, HardwareAssertionKey, HwaParams}
import xs.utils.perf.{LogUtilsOptions, LogUtilsOptionsKey, PerfCounterOptions, PerfCounterOptionsKey, XSPerfLevel}
import xs.utils.stage.XsStage
import xs.utils.cache.prefetch.BOPParameters
import xs.utils.sram.{SramBroadcastBundle, SramCtrlBundle}

class Top(implicit p: Parameters) extends LazyModule {
  override lazy val desiredName: String = "TestTop"
  private val cacheParams = p(L2ParamKey)
  private val l1dNode = TLClientNode(Seq(
    TLMasterPortParameters.v2(
      masters = Seq(
        TLMasterParameters.v1(
          name = name,
          sourceId = IdRange(0, 64),
          supportsProbe = TransferSizes(cacheParams.blockBytes)
        )
      ),
      channelBytes = TLChannelBeatBytes(cacheParams.blockBytes),
      minLatency = 1,
      echoFields = Nil,
      requestFields = Seq(AliasField(2), VaddrField(36), PrefetchField()),
      responseKeys = cacheParams.respKey
    )
  ))
  private val mmioNode: TLClientNode = TLClientNode(Seq(
    TLMasterPortParameters.v1(
      clients = Seq(TLMasterParameters.v1(
        name = "uncache",
        sourceId = IdRange(0, 16)
      ))
    ))
  )

  private val l1iNode = TLClientNode(Seq(
    TLMasterPortParameters.v1(
      clients = Seq(TLMasterParameters.v1(
        name = s"l1i",
        sourceId = IdRange(0, 64)
      ))
    )
  ))
  private val l2cache = LazyModule(new TL2CHICoupledL2)
  private val cXBar = LazyModule(new TLXbar)
  private val tpMetaSinkNode = l2cache.tpmeta_source_node.map(_.makeSink())
  private val tpMetaSourceNode = l2cache.tpmeta_sink_node.map(n => BundleBridgeSource(n.genOpt.get))
  // l2cache.tpmeta_sink_node.map(_ := tpMetaSourceNode.get)
  l2cache.tpmeta_sink_node.foreach(_ := tpMetaSourceNode.get)
  private val prefetchSourceNode = l2cache.pf_recv_node.map(n => BundleBridgeSource(n.genOpt.get))
  l2cache.pf_recv_node.foreach(_ := prefetchSourceNode.get)

  cXBar.node :*= l1iNode
  cXBar.node :*= l1dNode
  l2cache.managerNode :=* TLXbar() :=* BankBinder(2, 64) :*= l2cache.node :*= TLBuffer() :*= cXBar.node
  l2cache.mmioNode :*= mmioNode


  lazy val module = new Impl

  class Impl extends LazyModuleImp(this) {
    override def resetType: Module.ResetType.Type = Module.ResetType.Asynchronous
    val l1d = l1dNode.makeIOs()
    val l1i = l1iNode.makeIOs()
    val mmio = mmioNode.makeIOs()
    val chi = IO(l2cache.module.io_chi.cloneType)
    val prefetch = prefetchSourceNode.map(_.makeIOs())
    val tlb = IO(l2cache.module.io.l2_tlb_req.cloneType)
    val dft_func = IO(Input(new SramBroadcastBundle))
    val dft_reset = IO(Input(new DFTResetSignals()))
    val ram_ctl = IO(Input(new SramCtrlBundle))

    l2cache.module.io.l2_tlb_req <> tlb
    l2cache.module.io_chi <> chi
    l2cache.module.io.hartId := 0.U
    l2cache.module.io.pfCtrlFromCore := DontCare
    l2cache.module.io_nodeID := 0.U
    l2cache.module.io.debugTopDown := DontCare
    l2cache.module.io.l2_tlb_req <> DontCare
    if (l2cache.module.io.dft.func.isDefined) {
      l2cache.module.io.dft.func.get := dft_func
      l2cache.module.io.dft.reset.get := dft_reset
    }
    l2cache.module.io.ramctl := ram_ctl
    dontTouch(l2cache.module.io)

    tpMetaSinkNode.foreach(_.in.head._1.ready := true.B)
    tpMetaSourceNode.foreach(_.out.head._1 := DontCare)

    private val assertionNode = HardwareAssertion.placePipe(Int.MaxValue, moduleTop = true).map(_.head)
    HardwareAssertion.release(assertionNode, "hwa", "cpl2")
    assertionNode.foreach(_.hassert.bus.get.ready := true.B)
    val hwa = assertionNode.map(an => IO(Decoupled(UInt(p(HardwareAssertionKey).maxInfoBits.W))))
    if (assertionNode.isDefined) {
      hwa.get <> assertionNode.get.hassert.bus.get
      dontTouch(hwa.get)
    }
  }
}

class TopConfig extends Config((up, here, site) => {
  // case L2ParamKey => L2Param(ways = 8, sets = 1024, FPGAPlatform = true, clientCaches = Seq(L1Param(sets = 128, ways = 4)))
  case L2ParamKey => L2Param(
    ways = 8,
    sets = 1024,
    FPGAPlatform = true,
    tagECC = Some("secded"),
    dataECC = Some("secded"),
    enableTagECC = true,
    enableDataECC = true,
    dataCheck = None,
    prefetch = Seq(BOPParameters(), PrefetchReceiverParams()),
    clientCaches = Seq(L1Param(sets = 128, ways = 4, vaddrBitsOpt = Some(48))),
    hasMbist = true
  )
  case CHIIssue => Issue.Eb
  case EnableCHI => true
  case BankBitsKey => 1
  case MaxHartIdBits => 8
  case LogUtilsOptionsKey => LogUtilsOptions(false, false, true)
  case PerfCounterOptionsKey => PerfCounterOptions(false, false, XSPerfLevel.VERBOSE, 0)
  case HardwareAssertionKey => HwaParams(enable = true)
  case DecoupledCHI => true
})

object TopMain extends App {
  private val firtoolOpts = Seq(
    FirtoolOption("-O=release"),
    FirtoolOption("--disable-annotation-unknown"),
    FirtoolOption("--strip-debug-info"),
    FirtoolOption("--lower-memories"),
    FirtoolOption("--add-vivado-ram-address-conflict-synthesis-bug-workaround"),
    FirtoolOption("--lowering-options=noAlwaysComb," +
      " disallowPortDeclSharing, disallowLocalVariables," +
      " emittedLineLength=120, explicitBitcast," +
      " locationInfoStyle=plain, disallowMuxInlining"),
    FirtoolOption("--disable-all-randomization")
  )
  private val config = new TopConfig
  private val firrtlOpts = args

  private val top = DisableMonitors(p => LazyModule(new Top()(p)))(config)
  (new XsStage).execute(firrtlOpts, firtoolOpts :+ ChiselGeneratorAnnotation(() => top.module))
  FileRegisters.write(fileDir = "./build", filePrefix = "")
}