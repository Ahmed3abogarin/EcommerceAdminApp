package com.example.ecommerceadminapp

import android.content.Intent
import android.content.Intent.ACTION_GET_CONTENT
import android.content.Intent.EXTRA_ALLOW_MULTIPLE
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.ecommerceadminapp.databinding.ActivityMainBinding
import com.google.firebase.Firebase
import com.google.firebase.firestore.firestore
import com.google.firebase.storage.storage
import com.skydoves.colorpickerview.ColorEnvelope
import com.skydoves.colorpickerview.ColorPickerDialog
import com.skydoves.colorpickerview.listeners.ColorEnvelopeListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.UUID


class MainActivity : AppCompatActivity() {
    private val binding: ActivityMainBinding by lazy {
        ActivityMainBinding.inflate(layoutInflater)
    }
    private var selectedImages = mutableListOf<Uri>()
    private var selectedColors = mutableListOf<Int>()
    private val productsStorage = Firebase.storage.reference
    private val firestore = Firebase.firestore


    private fun getImagesByteArray(): List<ByteArray> {
        val imagesByteArray = mutableListOf<ByteArray>()
        selectedImages.forEach {
            val stream = ByteArrayOutputStream()
            val imageBmp = MediaStore.Images.Media.getBitmap(contentResolver, it)
            if (imageBmp.compress(Bitmap.CompressFormat.JPEG, 100, stream)) {
                imagesByteArray.add(stream.toByteArray())
            }
        }
        return imagesByteArray

    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        binding.colorsBtn.setOnClickListener {
            Toast.makeText(this, "THE FUCKING IS CLICKED", Toast.LENGTH_SHORT).show()
            ColorPickerDialog.Builder(this)
                .setTitle("Product color")
                .setPositiveButton("select", object : ColorEnvelopeListener {
                    override fun onColorSelected(envelope: ColorEnvelope?, fromUser: Boolean) {
                        envelope?.let {
                            selectedColors.add(it.color)
                            updateColors()
                        }
                    }

                })
                .setNegativeButton("cancel") { colorPicker, _ ->
                    colorPicker.dismiss()
                }.show()

        }


        // pick the images from user device
        val selectedImagesResultActivity =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    val intent = result.data

                    // mutailple images selected
                    if (intent?.clipData != null) {
                        val count = intent.clipData?.itemCount ?: 0
                        (0 until count).forEach {
                            val imageUri = intent.clipData?.getItemAt(it)?.uri
                            imageUri?.let {
                                selectedImages.add(it)
                            }

                        }
                    } else {
                        // if user only selected one image
                        val imageUri = intent?.data
                        imageUri?.let { selectedImages.add(it) }
                    }
                    updateImages()
                }

            }

        binding.imagesBtn.setOnClickListener {
            val intent = Intent(ACTION_GET_CONTENT)
            intent.putExtra(EXTRA_ALLOW_MULTIPLE, true)
            intent.type = "image/*"
            selectedImagesResultActivity.launch(intent)
        }


    }

    private fun updateImages() {
        binding.imagesCountTV.text = selectedImages.size.toString()
    }

    private fun updateColors() {
        var colors = ""
        selectedColors.forEach {
            colors = "$colors ${Integer.toHexString(it)}"

        }

        // set the colors to Ui
        binding.colorsTV.text = colors
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.my_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {

        if (item.itemId == R.id.saveMenuBtn) {
            val productValidation = validateInformation()
            if (!productValidation) {
                Toast.makeText(this, "Check yours input", Toast.LENGTH_SHORT).show()
                return false
            }

            saveProduct()
        }


        return super.onOptionsItemSelected(item)

    }

    private fun saveProduct() {
        val name = binding.productNameET.text.trim().toString()
        val category = binding.categoryET.text.trim().toString()
        val price = binding.priceET.text.trim().toString()
        val offerPercentage = binding.offerPercentageET.text.trim().toString()
        val description = binding.descriptionET.text.trim().toString()
        val sizes = getSizesList(binding.sizesET.text.toString().trim())

        // convert images to byte array so we can upload to firebase
        val imagesByteArray = getImagesByteArray()

        // to upload to firebase
        val images = mutableListOf<String>()

        lifecycleScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                showLoading()
            }

            try {
                async {
                    imagesByteArray.forEach {
                        // generate unique id for each image (using UUID)
                        val id = UUID.randomUUID().toString()
                        launch {
                            val imageStorage = productsStorage.child("product/images/$id")
                            val result = imageStorage.putBytes(it).await() // upload image
                            val downloadUrl = result.storage.downloadUrl.await().toString()
                            images.add(downloadUrl)
                        }
                    }

                }.await()
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    hideLoading()
                }

            }

            val product = Product(
                UUID.randomUUID().toString(),
                name,
                category,
                price.toFloat(),
                if (offerPercentage.isEmpty()) null else offerPercentage.toFloat(),
                description.ifEmpty { null },
                if (selectedColors.isEmpty()) null else selectedColors,
                sizes,
                images
            )

            firestore.collection("products").add(product)
                .addOnSuccessListener {
                    hideLoading()
                    Toast.makeText(
                        applicationContext,
                        "Product added successfully",
                        Toast.LENGTH_SHORT
                    ).show()
                }.addOnFailureListener {
                    hideLoading()
                    Toast.makeText(applicationContext, it.message.toString(), Toast.LENGTH_SHORT)
                        .show()

                }

        }

    }

    private fun hideLoading() {
        binding.apply {
            imagesBtn.visibility = View.VISIBLE
            imagesCountTV.visibility = View.VISIBLE
            colorsBtn.visibility = View.VISIBLE
            colorsTV.visibility = View.VISIBLE
            layoutProgress.visibility = View.GONE
            progressBar.visibility = View.GONE
        }

    }

    private fun showLoading() {
        binding.apply {
            imagesBtn.visibility = View.INVISIBLE
            imagesCountTV.visibility = View.INVISIBLE
            colorsBtn.visibility = View.INVISIBLE
            colorsTV.visibility = View.INVISIBLE
            layoutProgress.visibility = View.VISIBLE
            progressBar.visibility = View.VISIBLE
        }
    }

    private fun getSizesList(sizesStr: String): List<String>? {
        if (sizesStr.isEmpty())
            return null

        val sizeList = sizesStr.split(",")

        return sizeList
    }

    private fun validateInformation(): Boolean {
        if (binding.priceET.text.trim().toString().isEmpty())
            return false
        if (binding.productNameET.text.trim().toString().isEmpty())
            return false
        if (binding.categoryET.text.trim().toString().isEmpty())
            return false

        if (selectedImages.isEmpty())
            return false
        return true

    }
}