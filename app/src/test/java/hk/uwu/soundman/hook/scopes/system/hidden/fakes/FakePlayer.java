package hk.uwu.soundman.hook.scopes.system.hidden.fakes;

import android.os.DeadObjectException;

/** 具备 setVolume(float) 的假 IPlayer，记录调用参数并支持抛出原始异常。 */
public final class FakePlayer {
    public float lastVolume = Float.NaN;
    public int pauseCount;
    public int startCount;
    public RuntimeException throwOnSetVolume;
    public boolean throwDeadObjectOnPause;
    public boolean throwDeadObjectOnSetVolume;

    public void pause() throws DeadObjectException {
        if (throwDeadObjectOnPause) {
            throw new DeadObjectException("fake dead");
        }
        pauseCount += 1;
    }

    public void start() {
        startCount += 1;
    }

    public void setVolume(float volume) throws DeadObjectException {
        if (throwDeadObjectOnSetVolume) {
            throw new DeadObjectException("fake dead");
        }
        if (throwOnSetVolume != null) {
            throw throwOnSetVolume;
        }
        lastVolume = volume;
    }
}
