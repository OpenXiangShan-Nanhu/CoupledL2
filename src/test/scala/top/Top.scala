package top

import chisel3._
import chisel3.stage.ChiselGeneratorAnnotation
import circt.stage.{ChiselStage, FirtoolOption}
import coupledL2._
import coupledL2.tl2chi.{CHIIssue, Issue, PortIO, TL2CHICoupledL2}
import freechips.rocketchip.diplomacy.{IdRange, TransferSizes}
import freechips.rocketchip.tile.MaxHartIdBits
import freechips.rocketchip.tilelink.{BankBinder, TLBuffer, TLChannelBeatBytes, TLClientNode, TLMasterParameters, TLMasterPortParameters, TLXbar}
import org.chipsalliance.diplomacy.bundlebridge.BundleBridgeSource
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}
import org.chipsalliance.diplomacy.DisableMonitors
import org.chipsalliance.cde.config.{Config, Parameters}
import xs.utils.common.{AliasField, PrefetchField}
import coupledL2.prefetch.{BOPParameters, PrefetchReceiverParams}
import xs.utils.perf.{LogUtilsOptions, LogUtilsOptionsKey, PerfCounterOptions, PerfCounterOptionsKey, XSPerfLevel}

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
  private val mmioNode: Option[TLClientNode] = if (cacheParams.enableMmio)  Some(TLClientNode(Seq(
    TLMasterPortParameters.v1(
      clients = Seq(TLMasterParameters.v1(
        name = "uncache",
        sourceId = IdRange(0, 16)
      ))
    ))
  )) else None

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
  l2cache.mmioNode.foreach { l2mmio =>
    mmioNode.foreach { node =>
      l2mmio :*= node
    }
  }



  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val l1d = l1dNode.makeIOs()
    val l1i = l1iNode.makeIOs()
    val mmio = mmioNode.map(_.makeIOs())
    val chi = IO(l2cache.module.io_chi.cloneType)
    val prefetch = prefetchSourceNode.map(_.makeIOs())
    val tlb = IO(l2cache.module.io.l2_tlb_req.cloneType)

    l2cache.module.io.l2_tlb_req <> tlb
    l2cache.module.io_chi <> chi
    l2cache.module.io.hartId := 0.U
    l2cache.module.io.pfCtrlFromCore := DontCare
    l2cache.module.io_nodeID := 0.U
    l2cache.module.io.debugTopDown := DontCare
    l2cache.module.io.l2_tlb_req <> DontCare
    dontTouch(l2cache.module.io)

    tpMetaSinkNode.foreach(_.in.head._1.ready := true.B)
    tpMetaSourceNode.foreach(_.out.head._1 := DontCare)
  }
}

class TopConfig extends Config((up, here, site) => {
  // case L2ParamKey => L2Param(ways = 8, sets = 1024, FPGAPlatform = true, clientCaches = Seq(L1Param(sets = 128, ways = 4)))
  case L2ParamKey => L2Param(
     ways = 8,
     sets = 1024,
     FPGAPlatform = true,
     prefetch     = Seq(BOPParameters(), PrefetchReceiverParams()),
     clientCaches = Seq(L1Param(sets = 128, ways = 4, vaddrBitsOpt = Some(48))))
  case CHIIssue => Issue.Eb
  case EnableCHI => true
  case BankBitsKey => 1
  case MaxHartIdBits => 8
  case LogUtilsOptionsKey => LogUtilsOptions(false, false, true)
  case PerfCounterOptionsKey => PerfCounterOptions(false, false, XSPerfLevel.VERBOSE, 0)
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
  (new ChiselStage).execute(firrtlOpts, firtoolOpts :+ ChiselGeneratorAnnotation(() => top.module))
}