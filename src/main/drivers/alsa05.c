/* ALSA 0.5 driver for xmp
 * Copyright (C) 2000 Tijs van Bakel and Rob Adamson
 *
 * This file is part of the Extended Module Player and is distributed
 * under the terms of the GNU General Public License. See doc/COPYING
 * for more information.
 *
 * Fixed for ALSA 0.5 by Rob Adamson <R.Adamson@fitz.cam.ac.uk>
 * Sat, 29 Apr 2000 17:10:46 +0100 (BST)
 */

/* preliminary alsa 0.5 support, Tijs van Bakel, 02-03-2000.
 * only default values are supported and music sounds chunky
 */

/* Better ALSA 0.5 support, Rob Adamson, 16 Mar 2000.
 * Again, hard-wired fragment size & number and sample rate,
 * but it plays smoothly now.
 */

/* Now uses specified options - Rob Adamson, 20 Mar 2000 */

#ifdef HAVE_CONFIG_H
#include "config.h"
#endif

#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <sys/time.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <sys/asoundlib.h>

#include "driver.h"
#include "mixer.h"

static int init (struct context_data *ctx);
static void dshutdown (struct context_data *);
static void bufdump (struct context_data *, void *, int);
static void bufwipe (void);
static void flush (void);

static void dummy()
{
}

static char *help[] = {
	"frag=num,size", "Set the number and size (bytes) of fragments",
	"card <name>", "Select sound card to use",
	NULL
};

struct xmp_drv_info drv_alsa05 = {
	"alsa05",		/* driver ID */
	"ALSA 0.5 PCM audio",	/* driver description */
	help,			/* help */
	init,			/* init */
	dshutdown,		/* shutdown */
	dummy,			/* starttimer */
	flush,			/* stoptimer */
	bufdump,		/* bufdump */
	NULL
};

static snd_pcm_t *pcm_handle;

static int frag_num = 4;
static size_t frag_size = 4096;
static char *mybuffer = NULL;
static char *mybuffer_nextfree = NULL;
static char *card_name;


static int prepare_driver(void)
{
	int rc;

	rc = snd_pcm_plugin_prepare(pcm_handle, SND_PCM_CHANNEL_PLAYBACK);
	if (rc < 0) {
		printf("Unable to prepare plugin: %s\n", snd_strerror(rc));
		return rc;
	}

	return 0;
}

static int to_fmt(struct xmp_options *o)
{
	int fmt;

	if (o->resol == 0)
		return SND_PCM_SFMT_MU_LAW;

	if (o->resol == 8) {
		fmt = SND_PCM_SFMT_U8 | SND_PCM_SFMT_S8;
	} else {
		fmt = SND_PCM_SFMT_S16_LE | SND_PCM_SFMT_S16_BE |
		      SND_PCM_SFMT_U16_LE | SND_PCM_SFMT_U16_BE;

		if (o->big_endian) {
			fmt &= SND_PCM_SFMT_S16_BE | SND_PCM_SFMT_U16_BE;
		} else {
			fmt &= SND_PCM_SFMT_S16_LE | SND_PCM_SFMT_U16_LE;
		}
	}

	if (o->outfmt & XMP_FMT_UNS) {
		fmt &= SND_PCM_SFMT_U8|SND_PCM_SFMT_U16_LE|SND_PCM_SFMT_U16_BE;
	} else {
		fmt &= SND_PCM_SFMT_S8|SND_PCM_SFMT_S16_LE|SND_PCM_SFMT_S16_BE;
	}

	return fmt;
}

static int init(struct context_data *ctx)
{
	struct xmp_options *o = &ctx->o;
	snd_pcm_channel_params_t params;
	snd_pcm_channel_setup_t setup;
	int card = 0;
	int dev = 0;
	int rc;
	char *token, **parm;

	parm_init();
	chkparm2("frag", "%d,%d", &frag_num, &frag_size);
	if (frag_num > 8)
		frag_num = 8;
	if (frag_num < 4)
		frag_num = 4;
	if (frag_size > 65536)
		frag_size = 65536;
	if (frag_size < 16)
		frag_size = 16;
	chkparm1("card", card_name = token);
	//  card = snd_card_name(card_name); /* ? */
	//  dev = snd_defaults_pcm_device(); /* ? */
	parm_end();

	mybuffer = malloc(frag_size);
	if (mybuffer) {
		mybuffer_nextfree = mybuffer;
	} else {
		printf("Unable to allocate memory for mixer buffer\n");
		return XMP_ERR_DINIT;
	}

	if ((rc =
	     snd_pcm_open(&pcm_handle, card, dev, SND_PCM_OPEN_PLAYBACK)) < 0) {
		printf("Unable to initialize pcm device: %s\n",
		       snd_strerror(rc));
		return XMP_ERR_DINIT;
	}

	memset(&params, 0, sizeof(snd_pcm_channel_params_t));

	params.mode = SND_PCM_MODE_BLOCK;
	params.buf.block.frag_size = frag_size;
	params.buf.block.frags_min = 1;
	params.buf.block.frags_max = frag_num;

	//params.mode = SND_PCM_MODE_STREAM;
	//params.buf.stream.queue_size = 16384;
	//params.buf.stream.fill = SND_PCM_FILL_NONE;
	//params.buf.stream.max_fill = 0;

	params.channel = SND_PCM_CHANNEL_PLAYBACK;
	params.start_mode = SND_PCM_START_FULL;
	params.stop_mode = SND_PCM_STOP_ROLLOVER;

	params.format.interleave = 1;
	params.format.format = to_fmt(o);
	params.format.rate = o->freq;
	params.format.voices = (o->outfmt & XMP_FORMAT_MONO) ? 1 : 2;

	if ((rc = snd_pcm_plugin_params(pcm_handle, &params)) < 0) {
		printf("Unable to set output parameters: %s\n",
					snd_strerror(rc));
		return XMP_ERR_DINIT;
	}

	if (prepare_driver() < 0)
		return XMP_ERR_DINIT;

	memset(&setup, 0, sizeof(setup));
	setup.mode = SND_PCM_MODE_STREAM;
	setup.channel = SND_PCM_CHANNEL_PLAYBACK;

	if ((rc = snd_pcm_channel_setup(pcm_handle, &setup)) < 0) {
		printf("Unable to setup channel: %s\n", snd_strerror(rc));
		return XMP_ERR_DINIT;
	}

	return 0;
}

/* Build and write one tick (one PAL frame or 1/50 s in standard vblank
 * timed mods) of audio data to the output device.
 */
static void bufdump(struct context_data *ctx, void *b, int i)
{
	/* Note this assumes a fragment size of (frag_size) */
	while (i > 0) {
		size_t f = (frag_size) - (mybuffer_nextfree - mybuffer);
		size_t to_copy = (f < i) ? f : i;

		memcpy(mybuffer_nextfree, b, to_copy);
		b += to_copy;
		mybuffer_nextfree += to_copy;
		f -= to_copy;
		i -= to_copy;
		if (f == 0) {
			snd_pcm_plugin_write(pcm_handle, mybuffer, frag_size);
			mybuffer_nextfree = mybuffer;
		}
	}
}

static void dshutdown(struct context_data *ctx)
{
	snd_pcm_close(pcm_handle);
	free(mybuffer);
}

static void flush()
{
	snd_pcm_plugin_flush(pcm_handle, SND_PCM_CHANNEL_PLAYBACK);
	prepare_driver();
}