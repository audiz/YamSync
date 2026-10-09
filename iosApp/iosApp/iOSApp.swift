import SwiftUI
import AVFoundation
import MediaToolbox
import ComposeApp
import os

@_silgen_name("yandex_sign_track_url")
func c_yandex_sign_track_url(
    _ trackId: UnsafePointer<CChar>?,
    _ version: UnsafePointer<CChar>?,
    _ timestamp: UnsafePointer<CChar>?,
    _ authToken: UnsafePointer<CChar>?
) -> UnsafeMutablePointer<CChar>?

@_silgen_name("yandex_sign_batch_url")
func c_yandex_sign_batch_url(
    _ trackIds: UnsafePointer<CChar>?,
    _ version: UnsafePointer<CChar>?,
    _ timestamp: UnsafePointer<CChar>?,
    _ authToken: UnsafePointer<CChar>?
) -> UnsafeMutablePointer<CChar>?

@_silgen_name("yandex_free_string")
func c_yandex_free_string(_ ptr: UnsafeMutablePointer<CChar>?)

class SwiftTrackSignerProvider: NSObject, IosTrackSignerProvider {
    func signTrack(trackId: String, quality: String, timestamp: String, authToken: String) -> String? {
        return trackId.withCString { cTrackId in
            quality.withCString { cQuality in
                timestamp.withCString { cTimestamp in
                    authToken.withCString { cAuthToken in
                        guard let resPtr = c_yandex_sign_track_url(cTrackId, cQuality, cTimestamp, cAuthToken) else {
                            return nil
                        }
                        defer { c_yandex_free_string(resPtr) }
                        return String(cString: resPtr)
                    }
                }
            }
        }
    }

    func signBatch(trackIds: String, quality: String, timestamp: String, authToken: String) -> String? {
        return trackIds.withCString { cTrackIds in
            quality.withCString { cQuality in
                timestamp.withCString { cTimestamp in
                    authToken.withCString { cAuthToken in
                        guard let resPtr = c_yandex_sign_batch_url(cTrackIds, cQuality, cTimestamp, cAuthToken) else {
                            return nil
                        }
                        defer { c_yandex_free_string(resPtr) }
                        return String(cString: resPtr)
                    }
                }
            }
        }
    }
}

// =========================================================================
// 🎚 Realtime DSP Equalizer & Visualizer Tap for iOS
// =========================================================================

final class SwiftBiquadFilter {
    enum FilterType {
        case bypass
        case peaking
        case highPass
        case lowPass
    }

    private(set) var type: FilterType = .bypass

    private var b0: Float = 1.0
    private var b1: Float = 0.0
    private var b2: Float = 0.0
    private var a1: Float = 0.0
    private var a2: Float = 0.0

    private var d1L: Float = 0.0
    private var d2L: Float = 0.0
    private var d1R: Float = 0.0
    private var d2R: Float = 0.0

    func reset() {
        d1L = 0.0
        d2L = 0.0
        d1R = 0.0
        d2R = 0.0
    }

    func setBypass() {
        type = .bypass
        b0 = 1.0
        b1 = 0.0
        b2 = 0.0
        a1 = 0.0
        a2 = 0.0
    }

    func configurePeaking(sampleRate: Float, centerFreq: Float, gainDb: Float, q: Float = 1.4142) {
        if abs(gainDb) < 0.05 {
            setBypass()
            return
        }
        self.type = .peaking

        let clampedFreq = min(max(centerFreq, 10.0), sampleRate * 0.49)
        let w0 = (2.0 * Float.pi) * clampedFreq / sampleRate
        let cosW0 = cosf(w0)
        let sinW0 = sinf(w0)
        let alpha = sinW0 / (2.0 * max(q, 0.1))
        let a = powf(10.0, gainDb / 40.0)

        let a0 = 1.0 + alpha / a
        b0 = (1.0 + alpha * a) / a0
        b1 = (-2.0 * cosW0) / a0
        b2 = (1.0 - alpha * a) / a0
        a1 = (-2.0 * cosW0) / a0
        a2 = (1.0 - alpha / a) / a0
    }

    func configureHighPass(sampleRate: Float, cutoffFreq: Float, q: Float = 0.7071) {
        if cutoffFreq <= 21.0 {
            setBypass()
            return
        }
        self.type = .highPass

        let clampedFreq = min(max(cutoffFreq, 10.0), sampleRate * 0.45)
        let w0 = (2.0 * Float.pi) * clampedFreq / sampleRate
        let cosW0 = cosf(w0)
        let sinW0 = sinf(w0)
        let alpha = sinW0 / (2.0 * max(q, 0.1))

        let a0 = 1.0 + alpha
        b0 = ((1.0 + cosW0) / 2.0) / a0
        b1 = (-(1.0 + cosW0)) / a0
        b2 = ((1.0 + cosW0) / 2.0) / a0
        a1 = (-2.0 * cosW0) / a0
        a2 = (1.0 - alpha) / a0
    }

    func configureLowPass(sampleRate: Float, cutoffFreq: Float, q: Float = 0.7071) {
        if cutoffFreq >= 19900.0 {
            setBypass()
            return
        }
        self.type = .lowPass

        let clampedFreq = min(max(cutoffFreq, 100.0), sampleRate * 0.45)
        let w0 = (2.0 * Float.pi) * clampedFreq / sampleRate
        let cosW0 = cosf(w0)
        let sinW0 = sinf(w0)
        let alpha = sinW0 / (2.0 * max(q, 0.1))

        let a0 = 1.0 + alpha
        b0 = ((1.0 - cosW0) / 2.0) / a0
        b1 = (1.0 - cosW0) / a0
        b2 = ((1.0 - cosW0) / 2.0) / a0
        a1 = (-2.0 * cosW0) / a0
        a2 = (1.0 - alpha) / a0
    }

    @inline(__always)
    func processLeft(_ input: Float) -> Float {
        if type == .bypass { return input }
        let output = b0 * input + d1L
        d1L = b1 * input - a1 * output + d2L
        d2L = b2 * input - a2 * output
        return output
    }

    @inline(__always)
    func processRight(_ input: Float) -> Float {
        if type == .bypass { return input }
        let output = b0 * input + d1R
        d1R = b1 * input - a1 * output + d2R
        d2R = b2 * input - a2 * output
        return output
    }
}

final class SwiftEqualizerConfig {
    static let shared = SwiftEqualizerConfig()

    private var lock = os_unfair_lock_s()

    private var _isEnabled: Bool = false
    private var _hpfHz: Float = 20.0
    private var _lpfHz: Float = 20000.0
    private var _g0: Float = 0
    private var _g1: Float = 0
    private var _g2: Float = 0
    private var _g3: Float = 0
    private var _g4: Float = 0
    private var _g5: Float = 0
    private var _g6: Float = 0
    private var _g7: Float = 0
    private var _g8: Float = 0
    private var _g9: Float = 0
    private var _version: UInt64 = 0

    func update(
        isEnabled: Bool,
        hpfHz: Float,
        lpfHz: Float,
        g0: Float, g1: Float, g2: Float, g3: Float, g4: Float,
        g5: Float, g6: Float, g7: Float, g8: Float, g9: Float
    ) {
        os_unfair_lock_lock(&lock)
        _isEnabled = isEnabled
        _hpfHz = hpfHz
        _lpfHz = lpfHz
        _g0 = g0; _g1 = g1; _g2 = g2; _g3 = g3; _g4 = g4
        _g5 = g5; _g6 = g6; _g7 = g7; _g8 = g8; _g9 = g9
        _version &+= 1
        os_unfair_lock_unlock(&lock)
    }

    func readSettings(
        isEnabled: inout Bool,
        hpfHz: inout Float,
        lpfHz: inout Float,
        g0: inout Float, g1: inout Float, g2: inout Float, g3: inout Float, g4: inout Float,
        g5: inout Float, g6: inout Float, g7: inout Float, g8: inout Float, g9: inout Float,
        version: inout UInt64
    ) -> Bool {
        if os_unfair_lock_trylock(&lock) {
            let changed = (_version != version)
            if changed {
                isEnabled = _isEnabled
                hpfHz = _hpfHz
                lpfHz = _lpfHz
                g0 = _g0; g1 = _g1; g2 = _g2; g3 = _g3; g4 = _g4
                g5 = _g5; g6 = _g6; g7 = _g7; g8 = _g8; g9 = _g9
                version = _version
            }
            os_unfair_lock_unlock(&lock)
            return changed
        }
        return false
    }
}

class SwiftEqualizerListener: NSObject, IosEqualizerListener {
    func onEqualizerStateChanged(
        isEnabled: Bool,
        hpfHz: Float,
        lpfHz: Float,
        gain0: Float,
        gain1: Float,
        gain2: Float,
        gain3: Float,
        gain4: Float,
        gain5: Float,
        gain6: Float,
        gain7: Float,
        gain8: Float,
        gain9: Float
    ) {
        SwiftEqualizerConfig.shared.update(
            isEnabled: isEnabled,
            hpfHz: hpfHz,
            lpfHz: lpfHz,
            g0: gain0, g1: gain1, g2: gain2, g3: gain3, g4: gain4,
            g5: gain5, g6: gain6, g7: gain7, g8: gain8, g9: gain9
        )
    }
}

final class TapDSPContext {
    var sampleRate: Float = 44100.0
    var currentVersion: UInt64 = 0

    var isEnabled: Bool = false
    var hpfHz: Float = 20.0
    var lpfHz: Float = 20000.0
    var g0: Float = 0
    var g1: Float = 0
    var g2: Float = 0
    var g3: Float = 0
    var g4: Float = 0
    var g5: Float = 0
    var g6: Float = 0
    var g7: Float = 0
    var g8: Float = 0
    var g9: Float = 0

    let hpf = SwiftBiquadFilter()
    let b0 = SwiftBiquadFilter()
    let b1 = SwiftBiquadFilter()
    let b2 = SwiftBiquadFilter()
    let b3 = SwiftBiquadFilter()
    let b4 = SwiftBiquadFilter()
    let b5 = SwiftBiquadFilter()
    let b6 = SwiftBiquadFilter()
    let b7 = SwiftBiquadFilter()
    let b8 = SwiftBiquadFilter()
    let b9 = SwiftBiquadFilter()
    let lpf = SwiftBiquadFilter()

    var subCutFilter: Float = 0
    var kickFilter: Float = 0
    var bassFilter: Float = 0
    var midFilter: Float = 0
    var highMidFilter: Float = 0
    var lastUpdateNs: UInt64 = 0

    static let f0: Float = 31.0
    static let f1: Float = 63.0
    static let f2: Float = 125.0
    static let f3: Float = 250.0
    static let f4: Float = 500.0
    static let f5: Float = 1000.0
    static let f6: Float = 2000.0
    static let f7: Float = 4000.0
    static let f8: Float = 8000.0
    static let f9: Float = 16000.0

    init() {
        var dummyVersion: UInt64 = UInt64.max
        _ = SwiftEqualizerConfig.shared.readSettings(
            isEnabled: &isEnabled,
            hpfHz: &hpfHz,
            lpfHz: &lpfHz,
            g0: &g0, g1: &g1, g2: &g2, g3: &g3, g4: &g4,
            g5: &g5, g6: &g6, g7: &g7, g8: &g8, g9: &g9,
            version: &dummyVersion
        )
        currentVersion = dummyVersion
        recalculate()
    }

    func setSampleRate(_ rate: Float) {
        if rate > 8000 && rate != sampleRate {
            sampleRate = rate
            recalculate()
        }
    }

    func updateFiltersIfNeeded() {
        var newEnabled = isEnabled
        var newHpf = hpfHz
        var newLpf = lpfHz
        var newG0 = g0, newG1 = g1, newG2 = g2, newG3 = g3, newG4 = g4
        var newG5 = g5, newG6 = g6, newG7 = g7, newG8 = g8, newG9 = g9
        var newVersion = currentVersion

        if SwiftEqualizerConfig.shared.readSettings(
            isEnabled: &newEnabled,
            hpfHz: &newHpf,
            lpfHz: &newLpf,
            g0: &newG0, g1: &newG1, g2: &newG2, g3: &newG3, g4: &newG4,
            g5: &newG5, g6: &newG6, g7: &newG7, g8: &newG8, g9: &newG9,
            version: &newVersion
        ) {
            isEnabled = newEnabled
            hpfHz = newHpf
            lpfHz = newLpf
            g0 = newG0; g1 = newG1; g2 = newG2; g3 = newG3; g4 = newG4
            g5 = newG5; g6 = newG6; g7 = newG7; g8 = newG8; g9 = newG9
            currentVersion = newVersion
            recalculate()
        }
    }

    func recalculate() {
        guard isEnabled else {
            hpf.setBypass(); hpf.reset()
            b0.setBypass(); b0.reset()
            b1.setBypass(); b1.reset()
            b2.setBypass(); b2.reset()
            b3.setBypass(); b3.reset()
            b4.setBypass(); b4.reset()
            b5.setBypass(); b5.reset()
            b6.setBypass(); b6.reset()
            b7.setBypass(); b7.reset()
            b8.setBypass(); b8.reset()
            b9.setBypass(); b9.reset()
            lpf.setBypass(); lpf.reset()
            return
        }

        let sr = sampleRate > 8000 ? sampleRate : 44100.0
        hpf.configureHighPass(sampleRate: sr, cutoffFreq: hpfHz)

        b0.configurePeaking(sampleRate: sr, centerFreq: TapDSPContext.f0, gainDb: g0)
        b1.configurePeaking(sampleRate: sr, centerFreq: TapDSPContext.f1, gainDb: g1)
        b2.configurePeaking(sampleRate: sr, centerFreq: TapDSPContext.f2, gainDb: g2)
        b3.configurePeaking(sampleRate: sr, centerFreq: TapDSPContext.f3, gainDb: g3)
        b4.configurePeaking(sampleRate: sr, centerFreq: TapDSPContext.f4, gainDb: g4)
        b5.configurePeaking(sampleRate: sr, centerFreq: TapDSPContext.f5, gainDb: g5)
        b6.configurePeaking(sampleRate: sr, centerFreq: TapDSPContext.f6, gainDb: g6)
        b7.configurePeaking(sampleRate: sr, centerFreq: TapDSPContext.f7, gainDb: g7)
        b8.configurePeaking(sampleRate: sr, centerFreq: TapDSPContext.f8, gainDb: g8)
        b9.configurePeaking(sampleRate: sr, centerFreq: TapDSPContext.f9, gainDb: g9)

        lpf.configureLowPass(sampleRate: sr, cutoffFreq: lpfHz)
    }
}

private func tapInitCallback(
    tap: MTAudioProcessingTap,
    clientInfo: UnsafeMutableRawPointer?,
    tapStorageOut: UnsafeMutablePointer<UnsafeMutableRawPointer?>
) {
    let context = TapDSPContext()
    tapStorageOut.pointee = Unmanaged.passRetained(context).toOpaque()
}

private func tapFinalizeCallback(tap: MTAudioProcessingTap) {
    let storage = MTAudioProcessingTapGetStorage(tap)
    Unmanaged<TapDSPContext>.fromOpaque(storage).release()
}

private func tapPrepareCallback(
    tap: MTAudioProcessingTap,
    maxFrames: CMItemCount,
    processingFormat: UnsafePointer<AudioStreamBasicDescription>
) {
    let storage = MTAudioProcessingTapGetStorage(tap)
    let context = Unmanaged<TapDSPContext>.fromOpaque(storage).takeUnretainedValue()
    let sr = Float(processingFormat.pointee.mSampleRate)
    if sr > 8000 {
        context.setSampleRate(sr)
    }
}

private func tapUnprepareCallback(tap: MTAudioProcessingTap) {}

private func tapProcessCallback(
    tap: MTAudioProcessingTap,
    numberFrames: CMItemCount,
    flags: MTAudioProcessingTapFlags,
    bufferListInOut: UnsafeMutablePointer<AudioBufferList>,
    numberFramesOut: UnsafeMutablePointer<CMItemCount>,
    flagsOut: UnsafeMutablePointer<MTAudioProcessingTapFlags>
) {
    let status = MTAudioProcessingTapGetSourceAudio(
        tap,
        numberFrames,
        bufferListInOut,
        flagsOut,
        nil,
        numberFramesOut
    )
    guard status == noErr else { return }

    let frameCount = Int(numberFramesOut.pointee)
    guard frameCount > 0 else { return }

    let storage = MTAudioProcessingTapGetStorage(tap)
    let context = Unmanaged<TapDSPContext>.fromOpaque(storage).takeUnretainedValue()

    let abl = UnsafeMutableAudioBufferListPointer(bufferListInOut)
    guard abl.count > 0 else { return }

    let buffer0 = abl[0]
    guard let mData0 = buffer0.mData else { return }
    let floatPtr0 = mData0.assumingMemoryBound(to: Float.self)

    let isNonInterleavedStereo = abl.count > 1
    let floatPtr1: UnsafeMutablePointer<Float>? = isNonInterleavedStereo ?
        abl[1].mData?.assumingMemoryBound(to: Float.self) : nil

    let isInterleavedStereo = !isNonInterleavedStereo && buffer0.mNumberChannels == 2

    context.updateFiltersIfNeeded()

    let isEnabled = context.isEnabled
    let hpf = context.hpf
    let b0 = context.b0
    let b1 = context.b1
    let b2 = context.b2
    let b3 = context.b3
    let b4 = context.b4
    let b5 = context.b5
    let b6 = context.b6
    let b7 = context.b7
    let b8 = context.b8
    let b9 = context.b9
    let lpf = context.lpf

    let ALPHA_SUBCUT: Float   = 0.0057
    let ALPHA_KICK: Float     = 0.0157
    let ALPHA_BASS: Float     = 0.050
    let ALPHA_MID: Float      = 0.22
    let ALPHA_HIGH_MID: Float = 0.50

    var sumKick: Float = 0
    var sumBass: Float = 0
    var sumMid: Float = 0
    var sumHighMid: Float = 0
    var sumTreble: Float = 0

    var subCut = context.subCutFilter
    var kick = context.kickFilter
    var bass = context.bassFilter
    var mid = context.midFilter
    var highMid = context.highMidFilter

    if let floatPtr1 = floatPtr1 {
        for i in 0..<frameCount {
            var left = floatPtr0[i]
            var right = floatPtr1[i]

            if isEnabled {
                left = hpf.processLeft(left)
                right = hpf.processRight(right)

                left = b0.processLeft(left)
                right = b0.processRight(right)
                left = b1.processLeft(left)
                right = b1.processRight(right)
                left = b2.processLeft(left)
                right = b2.processRight(right)
                left = b3.processLeft(left)
                right = b3.processRight(right)
                left = b4.processLeft(left)
                right = b4.processRight(right)
                left = b5.processLeft(left)
                right = b5.processRight(right)
                left = b6.processLeft(left)
                right = b6.processRight(right)
                left = b7.processLeft(left)
                right = b7.processRight(right)
                left = b8.processLeft(left)
                right = b8.processRight(right)
                left = b9.processLeft(left)
                right = b9.processRight(right)

                left = lpf.processLeft(left)
                right = lpf.processRight(right)

                left = min(max(left, -1.0), 1.0)
                right = min(max(right, -1.0), 1.0)

                floatPtr0[i] = left
                floatPtr1[i] = right
            }

            let mono = (left + right) * 0.5

            subCut += ALPHA_SUBCUT * (mono - subCut)
            let clean = mono - subCut

            kick += ALPHA_KICK * (clean - kick)
            bass += ALPHA_BASS * (clean - bass)
            mid += ALPHA_MID * (clean - mid)
            highMid += ALPHA_HIGH_MID * (clean - highMid)

            sumKick += abs(kick)
            sumBass += abs(bass - kick)
            sumMid += abs(mid - bass)
            sumHighMid += abs(highMid - mid)
            sumTreble += abs(clean - highMid)
        }
    } else if isInterleavedStereo {
        for i in 0..<frameCount {
            var left = floatPtr0[i * 2]
            var right = floatPtr0[i * 2 + 1]

            if isEnabled {
                left = hpf.processLeft(left)
                right = hpf.processRight(right)

                left = b0.processLeft(left)
                right = b0.processRight(right)
                left = b1.processLeft(left)
                right = b1.processRight(right)
                left = b2.processLeft(left)
                right = b2.processRight(right)
                left = b3.processLeft(left)
                right = b3.processRight(right)
                left = b4.processLeft(left)
                right = b4.processRight(right)
                left = b5.processLeft(left)
                right = b5.processRight(right)
                left = b6.processLeft(left)
                right = b6.processRight(right)
                left = b7.processLeft(left)
                right = b7.processRight(right)
                left = b8.processLeft(left)
                right = b8.processRight(right)
                left = b9.processLeft(left)
                right = b9.processRight(right)

                left = lpf.processLeft(left)
                right = lpf.processRight(right)

                left = min(max(left, -1.0), 1.0)
                right = min(max(right, -1.0), 1.0)

                floatPtr0[i * 2] = left
                floatPtr0[i * 2 + 1] = right
            }

            let mono = (left + right) * 0.5

            subCut += ALPHA_SUBCUT * (mono - subCut)
            let clean = mono - subCut

            kick += ALPHA_KICK * (clean - kick)
            bass += ALPHA_BASS * (clean - bass)
            mid += ALPHA_MID * (clean - mid)
            highMid += ALPHA_HIGH_MID * (clean - highMid)

            sumKick += abs(kick)
            sumBass += abs(bass - kick)
            sumMid += abs(mid - bass)
            sumHighMid += abs(highMid - mid)
            sumTreble += abs(clean - highMid)
        }
    } else {
        for i in 0..<frameCount {
            var mono = floatPtr0[i]

            if isEnabled {
                mono = hpf.processLeft(mono)
                mono = b0.processLeft(mono)
                mono = b1.processLeft(mono)
                mono = b2.processLeft(mono)
                mono = b3.processLeft(mono)
                mono = b4.processLeft(mono)
                mono = b5.processLeft(mono)
                mono = b6.processLeft(mono)
                mono = b7.processLeft(mono)
                mono = b8.processLeft(mono)
                mono = b9.processLeft(mono)
                mono = lpf.processLeft(mono)

                mono = min(max(mono, -1.0), 1.0)
                floatPtr0[i] = mono
            }

            subCut += ALPHA_SUBCUT * (mono - subCut)
            let clean = mono - subCut

            kick += ALPHA_KICK * (clean - kick)
            bass += ALPHA_BASS * (clean - bass)
            mid += ALPHA_MID * (clean - mid)
            highMid += ALPHA_HIGH_MID * (clean - highMid)

            sumKick += abs(kick)
            sumBass += abs(bass - kick)
            sumMid += abs(mid - bass)
            sumHighMid += abs(highMid - mid)
            sumTreble += abs(clean - highMid)
        }
    }

    context.subCutFilter = subCut
    context.kickFilter = kick
    context.bassFilter = bass
    context.midFilter = mid
    context.highMidFilter = highMid

    let invCount = 1.0 / Float(frameCount)
    let avgKick = sumKick * invCount
    let avgBass = sumBass * invCount
    let avgMid = sumMid * invCount
    let avgHighMid = sumHighMid * invCount
    let avgTreble = sumTreble * invCount

    let bVisualizer1 = min(max(avgKick * 3.0, 0), 1.0)
    let bVisualizer2 = min(max(avgBass * 5.6, 0), 1.0)
    let bVisualizer3 = min(max(avgMid * 6.5, 0), 1.0)
    let bVisualizer4 = min(max(avgHighMid * 11.0, 0), 1.0)
    let bVisualizer5 = min(max(avgTreble * 17.5, 0), 1.0)

    let now = DispatchTime.now().uptimeNanoseconds
    if now - context.lastUpdateNs >= 16_000_000 {
        context.lastUpdateNs = now
        DispatchQueue.main.async {
            AudioVisualizer.shared.updateBands(b1: bVisualizer1, b2: bVisualizer2, b3: bVisualizer3, b4: bVisualizer4, b5: bVisualizer5)
        }
    }
}

class SwiftAudioTapAttacher: NSObject, IosAudioTapAttacher {
    func attachTap(playerItem: AVPlayerItem) {
        let asset = playerItem.asset

        let applyTapToTrack: (AVAssetTrack) -> Void = { [weak playerItem] audioTrack in
            guard let playerItem = playerItem else { return }

            var callbacks = MTAudioProcessingTapCallbacks(
                version: kMTAudioProcessingTapCallbacksVersion_0,
                clientInfo: nil,
                init: tapInitCallback,
                finalize: tapFinalizeCallback,
                prepare: tapPrepareCallback,
                unprepare: tapUnprepareCallback,
                process: tapProcessCallback
            )

            var tap: MTAudioProcessingTap?
            let err = MTAudioProcessingTapCreate(kCFAllocatorDefault, &callbacks, 1, &tap)
            guard err == noErr, let tap = tap else {
                print("SwiftAudioTapAttacher: Ошибка MTAudioProcessingTapCreate: \(err)")
                return
            }

            let specificParam = AVMutableAudioMixInputParameters(track: audioTrack)
            specificParam.audioTapProcessor = tap
            let specificMix = AVMutableAudioMix()
            specificMix.inputParameters = [specificParam]
            playerItem.audioMix = specificMix
            print("SwiftAudioTapAttacher: MTAudioProcessingTap успешно подключен к audioTrack (id: \(audioTrack.trackID))")
        }

        if let track = asset.tracks(withMediaType: .audio).first {
            applyTapToTrack(track)
            return
        }

        asset.loadValuesAsynchronously(forKeys: ["tracks"]) { [weak playerItem] in
            guard let playerItem = playerItem else { return }
            var error: NSError?
            let status = asset.statusOfValue(forKey: "tracks", error: &error)
            if status == .loaded {
                DispatchQueue.main.async {
                    if let track = asset.tracks(withMediaType: .audio).first {
                        applyTapToTrack(track)
                    }
                }
            } else if let error = error {
                print("SwiftAudioTapAttacher: Ошибка загрузки tracks: \(error.localizedDescription)")
            }
        }
    }
}

// =========================================================================
// MARK: - 📷 Нативный сканер QR-кодов YamSync (AVFoundation)
// =========================================================================

class SwiftQrScannerProvider: NSObject, IosQrScannerProvider {
    func launchQrScanner(callback: QrScanCallback) {
        DispatchQueue.main.async {
            guard let window = UIApplication.shared.windows.first(where: { $0.isKeyWindow }) ?? UIApplication.shared.windows.first,
                  let rootVC = window.rootViewController else {
                return
            }

            var topVC = rootVC
            while let presented = topVC.presentedViewController, !presented.isBeingDismissed {
                topVC = presented
            }

            let status = AVCaptureDevice.authorizationStatus(for: .video)
            switch status {
            case .authorized:
                let scannerVC = QrScannerViewController(callback: callback)
                scannerVC.modalPresentationStyle = .fullScreen
                topVC.present(scannerVC, animated: true, completion: nil)

            case .notDetermined:
                AVCaptureDevice.requestAccess(for: .video) { granted in
                    DispatchQueue.main.async {
                        if granted {
                            let scannerVC = QrScannerViewController(callback: callback)
                            scannerVC.modalPresentationStyle = .fullScreen
                            topVC.present(scannerVC, animated: true, completion: nil)
                        }
                    }
                }

            case .denied, .restricted:
                let alert = UIAlertController(
                    title: "Доступ к камере отключен",
                    message: "Чтобы сканировать QR-коды для синхронизации, разрешите приложению доступ к камере в Настройках iPhone.",
                    preferredStyle: .alert
                )
                alert.addAction(UIAlertAction(title: "Настройки", style: .default) { _ in
                    if let url = URL(string: UIApplication.openSettingsURLString) {
                        UIApplication.shared.open(url, options: [:], completionHandler: nil)
                    }
                })
                alert.addAction(UIAlertAction(title: "Отмена", style: .cancel, handler: nil))
                topVC.present(alert, animated: true, completion: nil)

            @unknown default:
                break
            }
        }
    }
}

class QrScannerViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    private let callback: QrScanCallback
    private var captureSession: AVCaptureSession?
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private var hasScanned = false
    private var torchBtn: UIButton?

    init(callback: QrScanCallback) {
        self.callback = callback
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        setupCamera()
        setupUI()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.bounds
    }

    override var prefersStatusBarHidden: Bool {
        return true
    }

    private func setupCamera() {
        guard let device = AVCaptureDevice.default(for: .video) else {
            showErrorAlert(message: "Камера не поддерживается на данном устройстве")
            return
        }

        let input: AVCaptureDeviceInput
        do {
            input = try AVCaptureDeviceInput(device: device)
        } catch {
            showErrorAlert(message: "Не удалось подключиться к камере: \(error.localizedDescription)")
            return
        }

        let session = AVCaptureSession()
        if session.canAddInput(input) {
            session.addInput(input)
        } else {
            showErrorAlert(message: "Не удалось инициализировать вход камеры")
            return
        }

        let metadataOutput = AVCaptureMetadataOutput()
        if session.canAddOutput(metadataOutput) {
            session.addOutput(metadataOutput)
            metadataOutput.setMetadataObjectsDelegate(self, queue: DispatchQueue.main)
            metadataOutput.metadataObjectTypes = [.qr]
        } else {
            showErrorAlert(message: "Не удалось настроить распознавание QR-кодов")
            return
        }

        let preview = AVCaptureVideoPreviewLayer(session: session)
        preview.frame = view.layer.bounds
        preview.videoGravity = .resizeAspectFill
        view.layer.addSublayer(preview)
        self.previewLayer = preview
        self.captureSession = session

        DispatchQueue.global(qos: .userInitiated).async {
            session.startRunning()
        }
    }

    private func setupUI() {
        let boxSize: CGFloat = min(view.bounds.width, view.bounds.height) * 0.65

        // Зеленая рамка прицела
        let box = UIView()
        box.translatesAutoresizingMaskIntoConstraints = false
        box.layer.borderColor = UIColor.systemGreen.cgColor
        box.layer.borderWidth = 3
        box.layer.cornerRadius = 20
        box.backgroundColor = .clear
        box.isUserInteractionEnabled = false
        view.addSubview(box)

        // Кнопка закрытия ("✕ Закрыть")
        let closeBtn = UIButton(type: .system)
        closeBtn.translatesAutoresizingMaskIntoConstraints = false
        closeBtn.setTitle("✕ Закрыть", for: .normal)
        closeBtn.setTitleColor(.white, for: .normal)
        closeBtn.titleLabel?.font = UIFont.systemFont(ofSize: 15, weight: .semibold)
        closeBtn.backgroundColor = UIColor.black.withAlphaComponent(0.65)
        closeBtn.layer.cornerRadius = 18
        closeBtn.contentEdgeInsets = UIEdgeInsets(top: 8, left: 16, bottom: 8, right: 16)
        closeBtn.addTarget(self, action: #selector(handleClose), for: .touchUpInside)
        view.addSubview(closeBtn)

        // Кнопка фонарика (фонарик доступен только при наличии torch)
        if let device = AVCaptureDevice.default(for: .video), device.hasTorch {
            let torch = UIButton(type: .system)
            torch.translatesAutoresizingMaskIntoConstraints = false
            torch.setTitle("🔦 Фонарик", for: .normal)
            torch.setTitleColor(.white, for: .normal)
            torch.titleLabel?.font = UIFont.systemFont(ofSize: 15, weight: .semibold)
            torch.backgroundColor = UIColor.black.withAlphaComponent(0.65)
            torch.layer.cornerRadius = 18
            torch.contentEdgeInsets = UIEdgeInsets(top: 8, left: 16, bottom: 8, right: 16)
            torch.addTarget(self, action: #selector(toggleTorch), for: .touchUpInside)
            view.addSubview(torch)
            self.torchBtn = torch

            NSLayoutConstraint.activate([
                torch.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 16),
                torch.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 16),
                torch.heightAnchor.constraint(equalToConstant: 36)
            ])
        }

        // Подсказка пользователю
        let hintLabel = UILabel()
        hintLabel.translatesAutoresizingMaskIntoConstraints = false
        hintLabel.text = "Наведите камеру на QR-код YamSync"
        hintLabel.textColor = .white
        hintLabel.textAlignment = .center
        hintLabel.font = UIFont.systemFont(ofSize: 15, weight: .medium)
        hintLabel.backgroundColor = UIColor.black.withAlphaComponent(0.6)
        hintLabel.layer.cornerRadius = 10
        hintLabel.layer.masksToBounds = true
        view.addSubview(hintLabel)

        NSLayoutConstraint.activate([
            box.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            box.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            box.widthAnchor.constraint(equalToConstant: boxSize),
            box.heightAnchor.constraint(equalToConstant: boxSize),

            closeBtn.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 16),
            closeBtn.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -16),
            closeBtn.heightAnchor.constraint(equalToConstant: 36),

            hintLabel.bottomAnchor.constraint(equalTo: box.topAnchor, constant: -24),
            hintLabel.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            hintLabel.leadingAnchor.constraint(greaterThanOrEqualTo: view.leadingAnchor, constant: 20),
            hintLabel.trailingAnchor.constraint(lessThanOrEqualTo: view.trailingAnchor, constant: -20),
            hintLabel.heightAnchor.constraint(equalToConstant: 38)
        ])
    }

    @objc private func handleClose() {
        stopAndDismiss(scannedCode: nil)
    }

    @objc private func toggleTorch() {
        guard let device = AVCaptureDevice.default(for: .video), device.hasTorch else { return }
        do {
            try device.lockForConfiguration()
            if device.torchMode == .on {
                device.torchMode = .off
                torchBtn?.setTitleColor(.white, for: .normal)
            } else {
                try device.setTorchModeOn(level: 1.0)
                torchBtn?.setTitleColor(.systemYellow, for: .normal)
            }
            device.unlockForConfiguration()
        } catch {}
    }

    private func stopAndDismiss(scannedCode: String?) {
        captureSession?.stopRunning()
        if let device = AVCaptureDevice.default(for: .video), device.hasTorch && device.torchMode == .on {
            try? device.lockForConfiguration()
            device.torchMode = .off
            device.unlockForConfiguration()
        }
        dismiss(animated: true) { [weak self] in
            if let code = scannedCode {
                self?.callback.onScanned(code: code)
            }
        }
    }

    private func showErrorAlert(message: String) {
        let alert = UIAlertController(title: "Ошибка камеры", message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "OK", style: .default) { [weak self] _ in
            self?.stopAndDismiss(scannedCode: nil)
        })
        present(alert, animated: true)
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
        guard !hasScanned,
              let metadataObj = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
              metadataObj.type == .qr,
              let stringVal = metadataObj.stringValue,
              !stringVal.isEmpty else { return }

        hasScanned = true

        let generator = UINotificationFeedbackGenerator()
        generator.notificationOccurred(.success)

        stopAndDismiss(scannedCode: stringVal)
    }
}

@main
struct iOSApp: App {
    init() {
        MainViewControllerKt.registerIosTrackSigner(provider: SwiftTrackSignerProvider())
        MainViewControllerKt.registerAudioTapAttacher(attacher: SwiftAudioTapAttacher())
        MainViewControllerKt.registerEqualizerListener(listener: SwiftEqualizerListener())
        MainViewControllerKt.registerQrScannerProvider(provider: SwiftQrScannerProvider())
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .onOpenURL { url in
                    DeepLinkHandler.shared.handleUrl(url: url.absoluteString)
                }
        }
    }
}

