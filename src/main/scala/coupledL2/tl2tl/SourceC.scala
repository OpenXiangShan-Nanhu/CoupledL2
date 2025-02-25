/** *************************************************************************************
  * Copyright (c) 2020-2021 Institute of Computing Technology, Chinese Academy of Sciences
  * Copyright (c) 2020-2021 Peng Cheng Laboratory
  *
  * XiangShan is licensed under Mulan PSL v2.
  * You can use this software according to the terms and conditions of the Mulan PSL v2.
  * You may obtain a copy of Mulan PSL v2 at:
  * http://license.coscl.org.cn/MulanPSL2
  *
  * THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
  * EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
  * MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
  *
  * See the Mulan PSL v2 for more details.
  * *************************************************************************************
  */

package coupledL2.tl2tl

import chisel3._
import chisel3.util._
import utility._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.tilelink._
import coupledL2._
import xs.utils.common.DirtyKey

//class SourceC(implicit p: Parameters) extends L2Module {
//  val io = IO(new Bundle() {
//    val in = Flipped(DecoupledIO(new Bundle() {
//      val task = new TaskBundle()
//      val data = new DSBlock()
//    }))
//    val out = DecoupledIO(new TLBundleC(edgeOut.bundle))
//    val resp = Output(new RespBundle)
//  })
//
//  val beat_valids = RegInit(VecInit(Seq.fill(mshrsAll) { // TODO: make sure there are enough entries
//    VecInit(Seq.fill(beatSize)(false.B))
//  }))
//  val block_valids = VecInit(beat_valids.map(_.asUInt.orR)).asUInt
//  val tasks = Reg(Vec(mshrsAll, new TaskBundle))
//  val datas = Reg(Vec(mshrsAll, new DSBlock))
//  val full = block_valids.andR
//  val selectOH = ParallelPriorityMux(~block_valids, (0 until mshrsAll).map(i => (1 << i).U))
//
//  selectOH.asBools.zipWithIndex.foreach {
//    case (sel, i) =>
//      when (sel && io.in.fire()) {
//        beat_valids(i).foreach(_ := true.B)
//        tasks(i) := io.in.bits.task
//        datas(i) := io.in.bits.data
//      }
//  }
//
//  def toTLBundleC(task: TaskBundle, data: UInt = 0.U) = {
//    val c = Wire(new TLBundleC(edgeOut.bundle))
//    c.opcode := task.opcode
//    c.param := task.param
//    c.size := offsetBits.U
//    c.source := task.mshrId
//    c.address := Cat(task.tag, task.set, task.off)
//    c.data := data
//    c.corrupt := false.B
//    c.user.lift(utility.ReqSourceKey).foreach(_ := task.reqSource)
//    c.echo.lift(DirtyKey).foreach(_ := task.dirty)
//    c
//  }
//
//  def getBeat(data: UInt, beatsOH: UInt): (UInt, UInt) = {
//    // get one beat from data according to beatsOH
//    require(data.getWidth == (blockBytes * 8))
//    require(beatsOH.getWidth == beatSize)
//    // next beat
//    val next_beat = ParallelPriorityMux(beatsOH, data.asTypeOf(Vec(beatSize, UInt((beatBytes * 8).W))))
//    val selOH = PriorityEncoderOH(beatsOH)
//    // remaining beats that haven't been sent out
//    val next_beatsOH = beatsOH & ~selOH
//    (next_beat, next_beatsOH)
//  }
//
//  val out_bundles = Wire(Vec(mshrsAll, io.out.cloneType))
//  out_bundles.zipWithIndex.foreach {
//    case (out, i) =>
//      out.valid := block_valids(i)
//      val data = datas(i).data
//      val beatsOH = beat_valids(i).asUInt
//      val (beat, next_beatsOH) = getBeat(data, beatsOH)
//      out.bits := toTLBundleC(tasks(i), beat)
//      val hasData = out.bits.opcode(0)
//
//      when (out.fire()) {
//        when (hasData) {
//          beat_valids(i) := VecInit(next_beatsOH.asBools)
//        }.otherwise {
//          beat_valids(i).foreach(_ := false.B)
//        }
//      }
//  }
//
//  TLArbiter.robin(edgeIn, io.out, out_bundles:_*)
//
//  io.in.ready := !full
//  assert(!full, "SourceC should never be full")
//
//  val (first, last, done, count) = edgeOut.count(io.out)
//  val isRelease = io.out.bits.opcode === TLMessages.Release
//  val isReleaseData = io.out.bits.opcode === TLMessages.ReleaseData
//  // [LRelease] TODO: resp from SourceC indicating w_release_sent may be deprecated
//  io.resp.valid := io.out.fire() && first && (isRelease || isReleaseData)
//  io.resp.mshrId := io.out.bits.source
//  io.resp.set := parseFullAddress(io.out.bits.address)._2
//  io.resp.tag := parseFullAddress(io.out.bits.address)._1
//  io.resp.respInfo := 0.U.asTypeOf(new RespInfoBundle)
//
//  XSPerfAccumulate("sourceC_full", full)
//}

class SourceCBlockBundle(implicit p: Parameters) extends L2Bundle {
  val blockSinkBReqEntrance = Bool()
  val blockMSHRReqEntrance = Bool()

  def apply() = 0.U.asTypeOf(this)
}

class SourceC(implicit p: Parameters) extends L2Module {
  val io = IO(new Bundle() {
    val in = Flipped(DecoupledIO(new Bundle() {
      val task = new TaskBundle()
      val data = new DSBlock()
    }))
    val out = DecoupledIO(new TLBundleC(edgeOut.bundle))
    val resp = Output(new RespBundle)
    val pipeStatusVec = Flipped(Vec(5, ValidIO(new PipeStatus)))
    val toReqArb = Output(new SourceCBlockBundle)
  })

  // We must keep SourceC FIFO, so a queue is used
  // Use customized SRAM: dual_port, max 256bits:
  val queue = Module(new Queue(new TaskBundle(), entries = mshrsAll, flow = true))
  val queueData0 = Module(new Queue(new DSBeat(), entries = mshrsAll, flow = true))
  val queueData1 = Module(new Queue(new DSBeat(), entries = mshrsAll, flow = true))
  queue.io.enq.valid := io.in.valid
  queue.io.enq.bits := io.in.bits.task
  io.in.ready := queue.io.enq.ready
  val enqData = io.in.bits.data.asTypeOf(Vec(beatSize, new DSBeat))
  queueData0.io.enq.valid := io.in.valid
  queueData0.io.enq.bits := enqData(0)
  queueData1.io.enq.valid := io.in.valid
  queueData1.io.enq.bits := enqData(1)
  // Add back pressure logic from SourceC
  // refer to GrantBuffer
  val sourceCQueueCnt = queue.io.count
  val noSpaceForSinkBReq = PopCount(VecInit(io.pipeStatusVec.tail.map { case s =>
    s.valid && (s.bits.fromA || s.bits.fromB)
  }).asUInt) + sourceCQueueCnt >= mshrsAll.U
  val noSpaceForMSHRReq = PopCount(VecInit(io.pipeStatusVec.tail.map { case s =>
    s.valid && (s.bits.fromA || s.bits.fromB)
  }).asUInt) + sourceCQueueCnt >= (mshrsAll-1).U

  io.toReqArb.blockSinkBReqEntrance := noSpaceForSinkBReq
  io.toReqArb.blockMSHRReqEntrance := noSpaceForMSHRReq

  // dequeued task, the only, ready to fire
  // WARNING: !it will reduce Release bandwidth to half!!!
  // TODO: change it the same way as GrantBuf
  val beatValids = RegInit(VecInit(Seq.fill(beatSize)(false.B)))
  val taskValid = beatValids.asUInt.orR
  val taskR = RegInit(0.U.asTypeOf(new Bundle() {
    val task = new TaskBundle()
    val data = new DSBlock()
  }))

  val dequeueReady = !taskValid
  queue.io.deq.ready := dequeueReady
  queueData0.io.deq.ready := dequeueReady
  queueData1.io.deq.ready := dequeueReady
  when(queue.io.deq.valid && dequeueReady) {
    beatValids.foreach(_ := true.B)
    taskR.task := queue.io.deq.bits
    taskR.data := Cat(queueData1.io.deq.bits.data, queueData0.io.deq.bits.data).asTypeOf(new DSBlock)
  }

  def toTLBundleC(task: TaskBundle, data: UInt = 0.U) = {
    val c = Wire(new TLBundleC(edgeOut.bundle))
    c.opcode := task.opcode
    c.param := task.param
    c.size := offsetBits.U
    c.source := task.mshrId
    c.address := Cat(task.tag, task.set, task.off)
    c.data := data
    c.corrupt := false.B
    c.user.lift(utility.ReqSourceKey).foreach(_ := task.reqSource)
    c.echo.lift(DirtyKey).foreach(_ := task.dirty)
    c
  }

  def getBeat(data: UInt, beatsOH: UInt): (UInt, UInt) = {
    // get one beat from data according to beatsOH
    require(data.getWidth == (blockBytes * 8))
    require(beatsOH.getWidth == beatSize)
    // next beat
    val next_beat = ParallelPriorityMux(beatsOH, data.asTypeOf(Vec(beatSize, UInt((beatBytes * 8).W))))
    val selOH = PriorityEncoderOH(beatsOH)
    // remaining beats that haven't been sent out
    val next_beatsOH = beatsOH & ~selOH
    (next_beat, next_beatsOH)
  }

  val data = taskR.data.data
  val beatsOH = beatValids.asUInt
  val (beat, next_beatsOH) = getBeat(data, beatsOH)

  io.out.valid := taskValid
  io.out.bits := toTLBundleC(taskR.task, beat)

  val hasData = io.out.bits.opcode(0)
  when (io.out.fire) {
    when (hasData) {
      beatValids := VecInit(next_beatsOH.asBools)
    }.otherwise {
      beatValids.foreach(_ := false.B)
    }
  }

  assert(io.in.ready, "SourceC should never be full") // WARNING

  // ========== Misc ============
  val (first, last, done, count) = edgeOut.count(io.out)
  val isRelease = io.out.bits.opcode === TLMessages.Release
  val isReleaseData = io.out.bits.opcode === TLMessages.ReleaseData

  // TODO: resp from SourceC indicating w_release_sent is deprecated, unused in MSHR
  io.resp.valid := io.out.fire && first && (isRelease || isReleaseData)
  io.resp.mshrId := io.out.bits.source
  io.resp.set := parseFullAddress(io.out.bits.address)._2
  io.resp.tag := parseFullAddress(io.out.bits.address)._1
  io.resp.respInfo := 0.U.asTypeOf(new RespInfoBundle)
}