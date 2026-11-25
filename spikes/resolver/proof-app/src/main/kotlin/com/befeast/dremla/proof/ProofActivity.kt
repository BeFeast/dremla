package com.befeast.dremla.proof

import android.app.Activity
import com.befeast.dremla.resolver.NewPipeResolver
import com.befeast.dremla.resolver.StreamResolver

/** Keeps the adapter in the debug dex graph; never resolves, signs in, or plays. */
class ProofActivity : Activity() {
    private val resolver: StreamResolver = NewPipeResolver()
}
