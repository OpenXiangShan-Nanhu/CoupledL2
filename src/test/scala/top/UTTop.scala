package top

import _root_.circt.stage.FirtoolOption
import chisel3._
import chisel3.util._
import chisel3.stage.ChiselGeneratorAnnotation
import freechips.rocketchip.diplomacy.{IdRange, TransferSizes}
import freechips.rocketchip.tile.MaxHartIdBits
import freechips.rocketchip.tilelink.{
  BankBinder,
  TLBuffer,
  TLChannelBeatBytes,
  TLClientNode,
  TLMasterParameters,
  TLMasterPortParameters,
  TLXbar
}
import org.chipsalliance.diplomacy.bundlebridge.BundleBridgeSource
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}
import org.chipsalliance.diplomacy.DisableMonitors
import org.chipsalliance.cde.config.{Config, Parameters}
import xs.utils.cache.common.{AliasField, BankBitsKey, L2ParamKey, PrefetchField, VaddrField}
import xs.utils.{DFTResetSignals, FileRegisters}
import xs.utils.cache.{EnableCHI, L1Param, L2Param}
import xs.utils.debug.{HardwareAssertion, HardwareAssertionKey, HwaParams}
import xs.utils.perf.{LogUtilsOptions, LogUtilsOptionsKey, PerfCounterOptions, PerfCounterOptionsKey, XSPerfLevel}
import xs.utils.stage.XsStage
import xs.utils.cache.prefetch.BOPParameters
import xs.utils.sram.{SramBroadcastBundle, SramCtrlBundle}
import coupledL2.prefetch.PrefetchReceiverParams
import coupledL2.tl2chi.{CHIIssue, DecoupledCHI, Issue, PortIO, SplitCHI, TL2CHICoupledL2, TL2CHIL2Module}

class SimpleEndpointCHI()(implicit p: Parameters) extends TL2CHIL2Module {
  val io = IO(new Bundle {
    val chi = Flipped(new PortIO(splitFlit = true))
  })

  val fakeCHIBundle = WireInit(0.U.asTypeOf(new PortIO(splitFlit = true)))
  io.chi <> fakeCHIBundle

  // Keep clock and reset
  val (_, cnt) = Counter(true.B, 10)
  dontTouch(cnt)

  dontTouch(io)
}

class UTTop(implicit p: Parameters) extends LazyModule {
  override lazy val desiredName: String = "TestTop"
  private val cacheParams = p(L2ParamKey)

  private val l1dNode = TLClientNode(
    Seq(
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
    )
  )

  private val l1iNode = TLClientNode(
    Seq(
      TLMasterPortParameters.v1(
        clients = Seq(
          TLMasterParameters.v1(
            name = s"l1i",
            sourceId = IdRange(0, 64)
          )
        )
      )
    )
  )

  private val mmioNode: TLClientNode = TLClientNode(
    Seq(
      TLMasterPortParameters.v1(
        clients = Seq(
          TLMasterParameters.v1(
            name = "uncache",
            sourceId = IdRange(0, 16)
          )
        )
      )
    )
  )

  private val l2cache = LazyModule(new TL2CHICoupledL2)
  private val cXBar = LazyModule(new TLXbar)

  cXBar.node :*= l1iNode
  cXBar.node :*= l1dNode

  l2cache.mmioNode :*= mmioNode
  l2cache.managerNode :=* TLXbar() :=* BankBinder(2, 64) :*= l2cache.node :*= TLBuffer() :*= cXBar.node

  lazy val module = new LazyModuleImp(this) {
    override def resetType: Module.ResetType.Type = Module.ResetType.Asynchronous
    val l1d = l1dNode.makeIOs()
    val l1i = l1iNode.makeIOs()
    val mmio = mmioNode.makeIOs()
    val io = IO(new Bundle {
      val l2Flush = Input(Bool())
      val l2FlushDone = Output(Bool())
    })

    l2cache.module.io <> DontCare
    l2cache.module.io_nodeID := 0.U
    l2cache.module.io_cpu_halt.foreach(_ := false.B)
    l2cache.module.io.l2Flush.foreach(_ := io.l2Flush)
    l2cache.module.io.l2FlushDone.foreach(io.l2FlushDone := _)

    dontTouch(l2cache.module.io)

    val chiEndpoint = Module(new SimpleEndpointCHI)
    l2cache.module.io_chi match {
      case chi: PortIO => chiEndpoint.io.chi <> chi
      case _ => assert(false, "Not PortIO")
    }
  }
}

class UTTopConfig
    extends Config((up, here, site) => {
      case L2ParamKey =>
        L2Param(
          ways = 8,
          sets = 256,
          FPGAPlatform = true,
          tagECC = Some("secded"),
          dataECC = Some("secded"),
          enableTagECC = true,
          enableDataECC = true,
          enableL2Flush = true,
          prefetch = Nil,
          clientCaches = Seq(L1Param(sets = 128, ways = 4, vaddrBitsOpt = Some(48), aliasBitsOpt = Some(2))),
          hasMbist = true
        )
      case CHIIssue              => Issue.Eb
      case EnableCHI             => true
      case BankBitsKey           => 1
      case MaxHartIdBits         => 8
      case LogUtilsOptionsKey    => LogUtilsOptions(false, false, true)
      case PerfCounterOptionsKey => PerfCounterOptions(false, false, XSPerfLevel.VERBOSE, 0)
      // case HardwareAssertionKey  => HwaParams(enable = true)
      case HardwareAssertionKey => HwaParams(enable = false)
      case DecoupledCHI         => false
      case SplitCHI             => true
    })

object UTTopMain extends App {
  private val firtoolOpts = Seq(
    FirtoolOption("-O=release"),
    FirtoolOption("--disable-annotation-unknown"),
    FirtoolOption("--strip-debug-info"),
    FirtoolOption("--lower-memories"),
    FirtoolOption("--add-vivado-ram-address-conflict-synthesis-bug-workaround"),
    FirtoolOption(
      "--lowering-options=noAlwaysComb," +
        " disallowPortDeclSharing, disallowLocalVariables," +
        " emittedLineLength=120, explicitBitcast," +
        " locationInfoStyle=plain, disallowMuxInlining"
    ),
    FirtoolOption("--disable-all-randomization")
  )
  private val config = new UTTopConfig
  private val firrtlOpts = args

  private val top = DisableMonitors(p => LazyModule(new UTTop()(p)))(config)
  (new XsStage).execute(firrtlOpts, firtoolOpts :+ ChiselGeneratorAnnotation(() => top.module))
  FileRegisters.write(fileDir = "./build", filePrefix = "")

}
